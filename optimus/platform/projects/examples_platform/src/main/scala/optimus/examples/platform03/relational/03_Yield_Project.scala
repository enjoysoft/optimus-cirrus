/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package optimus.examples.platform03.relational

import optimus.platform._
import optimus.platform.relational._

object YieldProject extends LegacyOptimusApp {

  final case class Holding(val symbol: String, val quantity: Int) {}

  // generate Holding objects
  val holds =
    for (pos <- from(Data.getPositions()))
      yield Holding(pos.symbol, pos.quantity)

  for (h <- Query.execute(holds)) {
    println(h)
  }

  // create anonymous type
  println("\nAnonymous class example")
  println("symbol\t quantity")
  val anonymous =
    for (pos <- from(Data.getPositions()))
      yield new { val symbol = pos.symbol; val quantity = pos.quantity }

  for (o <- Query.execute(anonymous)) {
    println(o.symbol + " | " + o.quantity)
  }

}
