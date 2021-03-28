package lila.storm

import reactivemongo.api.bson.BSONNull
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import lila.db.AsyncColl
import lila.db.dsl._
import lila.memo.CacheApi
import lila.puzzle.PuzzleColls

/* The difficulty of storm should remain constant!
 * Be very careful when adjusting the selector.
 * Use the grafana average rating per slice chart.
 */
final class StormSelector(colls: PuzzleColls, cacheApi: CacheApi)(implicit ec: ExecutionContext) {

  import StormBsonHandlers._

  def easySet: Fu[List[StormPuzzle]] = currentEasy.get {}

  def fullSet: Fu[List[StormPuzzle]] = easySet flatMap { easy =>
    currentHard.get {} map { easy ::: _ }
  }

  private val theme        = lila.puzzle.PuzzleTheme.mix.key.value
  private val tier         = lila.puzzle.PuzzleTier.Good.key
  private val maxDeviation = 85

  /* for path boundaries:
   * 800,  900,  1000, 1100, 1200, 1270, 1340, 1410, 1480, 1550, 1620,
   * 1690, 1760, 1830, 1900, 2000, 2100, 2200, 2350, 2500, 2650, 2800
   */

  private val currentEasy = cacheApi.unit[List[StormPuzzle]] {
    _.refreshAfterWrite(6 seconds)
      .buildAsyncFuture { _ =>
        fetchPuzzlesForBuckets(
          "easy",
          List(
            1000 -> 7,
            1150 -> 7,
            1300 -> 8,
            1450 -> 9,
            1600 -> 10,
            1750 -> 11,
            1900 -> 13,
            2050 -> 15,
            2199 -> 17,
            2349 -> 19,
            2499 -> 21
          )
        )
      }
  }

  private val currentHard = cacheApi.unit[List[StormPuzzle]] {
    _.refreshAfterWrite(5 minutes)
      .buildAsyncFuture { _ =>
        fetchPuzzlesForBuckets(
          "hard",
          List(
            2649 -> 22,
            2799 -> 23
          )
        )
      }
  }

  private def fetchPuzzlesForBuckets(section: String, buckets: List[(Int, Int)]) = {
    val poolSize = buckets.map(_._2).sum
    colls
      .path {
        _.aggregateList(poolSize) { framework =>
          import framework._
          val fenColorRegex = $doc(
            "$regexMatch" -> $doc(
              "input" -> "$fen",
              "regex" -> { if (scala.util.Random.nextBoolean()) " w " else " b " }
            )
          )
          Facet(
            buckets.map { case (rating, nbPuzzles) =>
              println(
                lila.db.BSON debug $doc(
                  "min" $lte f"${theme}_${tier}_${rating}%04d",
                  "max" $gte f"${theme}_${tier}_${if (rating > 2700) 9999 else rating}%04d"
                )
              )
              rating.toString -> List(
                Match(
                  $doc(
                    "min" $lte f"${theme}_${tier}_${rating}%04d",
                    "max" $gte f"${theme}_${tier}_${if (rating > 2700) 9999 else rating}%04d"
                  )
                ),
                Sample(1),
                Project($doc("_id" -> false, "ids" -> true)),
                UnwindField("ids"),
                // ensure we have enough after filtering deviation & color
                Sample(nbPuzzles * 7),
                PipelineOperator(
                  $doc(
                    "$lookup" -> $doc(
                      "from" -> colls.puzzle.name.value,
                      "as"   -> "puzzle",
                      "let"  -> $doc("id" -> "$ids"),
                      "pipeline" -> $arr(
                        $doc(
                          "$match" -> $doc(
                            "$expr" -> $doc(
                              "$and" -> $arr(
                                $doc("$eq"  -> $arr("$_id", "$$id")),
                                $doc("$lte" -> $arr("$glicko.d", maxDeviation)),
                                fenColorRegex
                              )
                            )
                          )
                        ),
                        $doc(
                          "$project" -> $doc(
                            "fen"    -> true,
                            "line"   -> true,
                            "rating" -> $doc("$toInt" -> "$glicko.r")
                          )
                        )
                      )
                    )
                  )
                ),
                UnwindField("puzzle"),
                Sample(nbPuzzles),
                ReplaceRootField("puzzle")
              )
            }
          ) -> List(
            Project($doc("all" -> $doc("$setUnion" -> buckets.map(r => s"$$${r._1}")))),
            UnwindField("all"),
            ReplaceRootField("all"),
            Sort(Ascending("rating"))
          )
        }.map {
          _.flatMap(StormPuzzleBSONReader.readOpt)
        }
      }
      .mon(_.storm.selector.time(section))
      .addEffect { puzzles =>
        monitor(section, puzzles.toVector, poolSize)
      }
  }

  private def monitor(section: String, puzzles: Vector[StormPuzzle], poolSize: Int): Unit = {
    val nb = puzzles.size
    lila.mon.storm.selector.count(section).record(nb)
    if (nb < poolSize * 0.9)
      logger.warn(s"Selector wanted $poolSize puzzles, only got $nb")
    if (nb > 1) {
      val rest = puzzles.toVector drop 1
      lila.common.Maths.mean(rest.map(_.rating)) foreach { r =>
        lila.mon.storm.selector.rating(section).record(r.toInt).unit
      }
      (0 to poolSize by 10) foreach { i =>
        val slice = rest drop i take 10
        lila.common.Maths.mean(slice.map(_.rating)) foreach { r =>
          lila.mon.storm.selector.ratingSlice(section, i).record(r.toInt)
        }
      }
    }
  }
}
