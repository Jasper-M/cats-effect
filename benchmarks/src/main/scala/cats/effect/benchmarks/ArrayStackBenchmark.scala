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

package cats.effect.benchmarks

import cats.effect.ArrayStack

import org.openjdk.jmh.annotations._

import java.util.concurrent.TimeUnit

/**
 * To do comparative benchmarks between versions:
 *
 * benchmarks/run-benchmark ArrayStackBenchmark
 *
 * This will generate results in `benchmarks/results`.
 *
 * Or to run the benchmark from within sbt:
 *
 * Jmh / run -i 10 -wi 10 -f 2 -t 1 cats.effect.benchmarks.ArrayStackBenchmark
 *
 * Which means "10 iterations", "10 warm-up iterations", "2 forks", "1 thread". Please note that
 * benchmarks should be usually executed at least in 10 iterations (as a rule of thumb), but
 * more is better.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class ArrayStackBenchmark {

  @Param(Array("1000000"))
  var size: Int = _

  @Benchmark
  def push(): Unit = {
    val stack: ArrayStack[Integer] = ArrayStack[Integer](size)

    var i = 0
    while (i < size) {
      stack.push(i)
      i += 1
    }
  }
}
