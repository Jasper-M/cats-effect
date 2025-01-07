/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package unsafe

import cats.effect.std.Mutex
import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._

import org.typelevel.scalaccompat.annotation._

import scala.annotation.tailrec
import scala.collection.mutable.LongMap
import scala.scalanative.libc.errno._
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException

object KqueueSystem extends PollingSystem {

  import event._
  import eventImplicits._

  private final val MaxEvents = 64

  type Api = FileDescriptorPoller

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): FileDescriptorPoller =
    new FileDescriptorPollerImpl(ctx)

  def makePoller(): Poller = {
    val fd = kqueue()
    if (fd == -1)
      throw new IOException(fromCString(strerror(errno)))
    new Poller(fd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long): Boolean =
    poller.poll(nanos)

  def processReadyEvents(poller: Poller): Boolean =
    poller.processReadyEvents()

  def needsPoll(poller: Poller): Boolean =
    poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = ()

  def metrics(poller: Poller): PollerMetrics = PollerMetrics.noop

  private final class FileDescriptorPollerImpl private[KqueueSystem] (
      ctx: PollingContext[Poller]
  ) extends FileDescriptorPoller {
    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Mutex[IO], Mutex[IO]).mapN {
          new PollHandle(ctx, fd, _, _)
        }
      }
  }

  // A kevent is identified by the (ident, filter) pair; there may only be one unique kevent per kqueue
  @inline private def encodeKevent(ident: Int, filter: Short): Long =
    (filter.toLong << 32) | ident.toLong

  private final class PollHandle(
      ctx: PollingContext[Poller],
      fd: Int,
      readMutex: Mutex[IO],
      writeMutex: Mutex[IO]
  ) extends FileDescriptorPollHandle {

    def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      readMutex.lock.surround {
        a.tailRecM { a =>
          f(a).flatTap { r =>
            if (r.isRight)
              IO.unit
            else
              IO.async[Unit] { kqcb =>
                IO.async_[Option[IO[Unit]]] { cb =>
                  ctx.accessPoller { kqueue =>
                    kqueue.evSet(fd, EVFILT_READ, EV_ADD.toUShort, kqcb)
                    cb(Right(Some(IO(kqueue.removeCallback(fd, EVFILT_READ)))))
                  }
                }

              }
          }
        }
      }

    def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      writeMutex.lock.surround {
        a.tailRecM { a =>
          f(a).flatTap { r =>
            if (r.isRight)
              IO.unit
            else
              IO.async[Unit] { kqcb =>
                IO.async_[Option[IO[Unit]]] { cb =>
                  ctx.accessPoller { kqueue =>
                    kqueue.evSet(fd, EVFILT_WRITE, EV_ADD.toUShort, kqcb)
                    cb(Right(Some(IO(kqueue.removeCallback(fd, EVFILT_WRITE)))))
                  }
                }
              }
          }
        }
      }

  }

  final class Poller private[KqueueSystem] (kqfd: Int) {

    private[this] val buffer = new Array[Byte](sizeof[kevent64_s].toInt * MaxEvents)
    @inline private[this] def eventlist =
      buffer.atUnsafe(0).asInstanceOf[Ptr[kevent64_s]]
    private[this] var eventCount = 0

    private[this] val callbacks = new LongMap[Either[Throwable, Unit] => Unit]()

    private[KqueueSystem] def evSet(
        ident: Int,
        filter: Short,
        flags: CUnsignedShort,
        cb: Either[Throwable, Unit] => Unit
    ): Unit = {
      val event = eventlist + eventCount.toLong

      event.ident = ident.toULong
      event.filter = filter
      event.flags = (flags.toInt | EV_ONESHOT).toUShort

      callbacks.update(encodeKevent(ident, filter), cb)

      eventCount += 1
    }

    private[KqueueSystem] def removeCallback(ident: Int, filter: Short): Unit = {
      callbacks -= encodeKevent(ident, filter)
      ()
    }

    private[KqueueSystem] def close(): Unit =
      if (unistd.close(kqfd) != 0)
        throw new IOException(fromCString(strerror(errno)))

    private[KqueueSystem] def poll(timeout: Long): Boolean = {

      val timeoutSpec =
        if (timeout <= 0) null
        else {
          val ts = stackalloc[timespec]()
          ts.tv_sec = timeout / 1000000000
          ts.tv_nsec = timeout % 1000000000
          ts
        }

      val flags = if (timeout == 0) KEVENT_FLAG_IMMEDIATE else KEVENT_FLAG_NONE

      val rtn = kevent64(
        kqfd,
        eventlist,
        eventCount,
        eventlist,
        MaxEvents,
        flags.toUInt,
        timeoutSpec
      )

      if (rtn >= 0) {
        eventCount = rtn
        rtn > 0
      } else if (errno == EINTR) { // spurious wake-up by signal
        false
      } else {
        throw new IOException(fromCString(strerror(errno)))
      }
    }

    @tailrec
    private[KqueueSystem] def processReadyEvents(): Boolean = {
      var i = 0
      var event = eventlist
      while (i < eventCount) {
        val kevent = encodeKevent(event.ident.toInt, event.filter)
        val cb = callbacks.getOrNull(kevent)
        callbacks -= kevent

        if (cb ne null)
          cb(
            if ((event.flags.toLong & EV_ERROR) != 0)
              Left(new IOException(fromCString(strerror(event.data.toInt))))
            else Either.unit
          )

        i += 1
        event += 1
      }

      if (eventCount >= MaxEvents) { // drain the ready list
        val rtn =
          kevent64(kqfd, null, 0, eventlist, MaxEvents, KEVENT_FLAG_IMMEDIATE.toUInt, null)
        if (rtn >= 0) {
          eventCount = rtn
          processReadyEvents()
        } else {
          throw new IOException(fromCString(strerror(errno)))
        }
      } else {
        eventCount = 0
        true
      }
    }

    private[KqueueSystem] def needsPoll(): Boolean = eventCount > 0 || callbacks.nonEmpty
  }

  @nowarn212
  @extern
  private object event {
    // Derived from https://opensource.apple.com/source/xnu/xnu-7195.81.3/bsd/sys/event.h.auto.html

    final val EVFILT_READ = -1
    final val EVFILT_WRITE = -2

    final val KEVENT_FLAG_NONE = 0x000000
    final val KEVENT_FLAG_IMMEDIATE = 0x000001

    final val EV_ADD = 0x0001
    final val EV_DELETE = 0x0002
    final val EV_ONESHOT = 0x0010
    final val EV_CLEAR = 0x0020
    final val EV_ERROR = 0x4000

    type kevent64_s

    def kqueue(): CInt = extern

    def kevent64(
        kq: CInt,
        changelist: Ptr[kevent64_s],
        nchanges: CInt,
        eventlist: Ptr[kevent64_s],
        nevents: CInt,
        flags: CUnsignedInt,
        timeout: Ptr[timespec]
    ): CInt = extern

  }

  private object eventImplicits {

    implicit final class kevent64_sOps(kevent64_s: Ptr[kevent64_s]) {
      def ident: CUnsignedLongInt = !kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]]
      def ident_=(ident: CUnsignedLongInt): Unit =
        !kevent64_s.asInstanceOf[Ptr[CUnsignedLongInt]] = ident

      def filter: CShort = !(kevent64_s.asInstanceOf[Ptr[CShort]] + 4)
      def filter_=(filter: CShort): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CShort]] + 4) = filter

      def flags: CUnsignedShort = !(kevent64_s.asInstanceOf[Ptr[CUnsignedShort]] + 5)
      def flags_=(flags: CUnsignedShort): Unit =
        !(kevent64_s.asInstanceOf[Ptr[CUnsignedShort]] + 5) = flags

      def data: CLong = !(kevent64_s.asInstanceOf[Ptr[CLong]] + 2)

      def udata: Ptr[Byte] = !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3)
      def udata_=(udata: Ptr[Byte]): Unit =
        !(kevent64_s.asInstanceOf[Ptr[Ptr[Byte]]] + 3) = udata
    }

    implicit val kevent64_sTag: Tag[kevent64_s] =
      Tag.materializeCArrayTag[Byte, Nat.Digit2[Nat._4, Nat._8]].asInstanceOf[Tag[kevent64_s]]
  }
}
