package org.template.productranking

import org.apache.predictionio.controller.P2LAlgorithm
import org.apache.predictionio.controller.Params
import org.apache.predictionio.data.storage.BiMap

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.evaluation.RankingMetrics
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.{Rating => MLlibRating}

import grizzled.slf4j.Logger

import scala.collection.parallel.immutable.ParVector

case class ALSAlgorithmParams(
  rank: Int,
  numIterations: Int,
  lambda: Double,
  seed: Option[Long]) extends Params

class ALSModel(
  val rank: Int,
  val userFeatures: Map[Int, Array[Double]],
  val productFeatures: Map[Int, Array[Double]],
  val userStringIntMap: BiMap[String, Int],
  val itemStringIntMap: BiMap[String, Int]
) extends Serializable {

  @transient lazy val itemIntStringMap = itemStringIntMap.inverse

  override def toString = {
    s" rank: ${rank}" +
    s" userFeatures: [${userFeatures.size}]" +
    s"(${userFeatures.take(2).toList}...)" +
    s" productFeatures: [${productFeatures.size}]" +
    s"(${productFeatures.take(2).toList}...)" +
    s" userStringIntMap: [${userStringIntMap.size}]" +
    s"(${userStringIntMap.take(2).toString}...)]" +
    s" itemStringIntMap: [${itemStringIntMap.size}]" +
    s"(${itemStringIntMap.take(2).toString}...)]"
  }
}

class ALSAlgorithm(val ap: ALSAlgorithmParams)
  extends P2LAlgorithm[PreparedData, ALSModel, Query, PredictedResult] {

  @transient lazy val logger = Logger[this.type]

  def train(sc: SparkContext, data: PreparedData): ALSModel = {
    require(!data.viewEvents.take(1).isEmpty,
      s"viewEvents in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    require(!data.users.take(1).isEmpty,
      s"users in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    require(!data.items.take(1).isEmpty,
      s"items in PreparedData cannot be empty." +
      " Please check if DataSource generates TrainingData" +
      " and Preprator generates PreparedData correctly.")
    // create User and item's String ID to integer index BiMap
    val userStringIntMap = BiMap.stringInt(data.users.keys)
    val itemStringIntMap = BiMap.stringInt(data.items.keys)

    val mllibRatings = data.viewEvents
      .map { r =>
        // Convert user and item String IDs to Int index for MLlib
        val uindex = userStringIntMap.getOrElse(r.user, -1)
        val iindex = itemStringIntMap.getOrElse(r.item, -1)

        if (uindex == -1)
          logger.info(s"Couldn't convert nonexistent user ID ${r.user}"
            + " to Int index.")

        if (iindex == -1)
          logger.info(s"Couldn't convert nonexistent item ID ${r.item}"
            + " to Int index.")

        ((uindex, iindex), 1)
      }.filter { case ((u, i), v) =>
        // keep events with valid user and item index
        (u != -1) && (i != -1)
      }.reduceByKey(_ + _) // aggregate all view events of same user-item pair
      .map { case ((u, i), v) =>
        // MLlibRating requires integer index for user and item
        MLlibRating(u, i, v)
      }

    // MLLib ALS cannot handle empty training data.
    require(!mllibRatings.take(1).isEmpty,
      s"mllibRatings cannot be empty." +
      " Please check if your events contain valid user and item ID.")

    // seed for MLlib ALS
    val seed = ap.seed.getOrElse(System.nanoTime)

    logger.info(s"Training with parameters [rank: ${ap.rank}, " +
      s"num iterations: ${ap.numIterations}, " +
      s"lambda: ${ap.lambda}, " +
      s"seed: ${seed}]\n" +
      s"Event count: ${data.viewEvents.count}")

    val m = ALS.trainImplicit(
      ratings = mllibRatings,
      rank = ap.rank,
      iterations = ap.numIterations,
      lambda = ap.lambda,
      blocks = -1,
      alpha = 1.0,
      seed = seed)

    // Training-time evaluation using Spark RankingMetrics - sometimes we get a stack overflow

    // Map ratings to 1 or 0, 1 indicating an item that should be recommended
    val binarizedRatings = mllibRatings.map(r => MLlibRating(r.user, r.product,
      if (r.rating > 2.5) 1.0 else 0.0)).cache()

    // Define a function to scale ratings from 0 to 1
    def scaledRating(r: MLlibRating): MLlibRating = {
      val scaledRating = math.max(math.min(r.rating, 1.0), 0.0)
      MLlibRating(r.user, r.product, scaledRating)
    }

    // Get sorted top ten predictions for each user and then scale from [0, 1]
    val userRecommended = m.recommendProductsForUsers(10).map { case (user, recs) =>
      (user, recs.map(scaledRating))
    }

    // Assume that any item a user clicked through to (which maps to a 1) is a relevant document
    // Compare with top ten most relevant documents
    val userItems = binarizedRatings.groupBy(_.user)
    val relevantDocuments = userItems.join(userRecommended).map { case (user, (actual,
      predictions)) =>
        (predictions.map(_.product), actual.filter(_.rating > 0.0).map(_.product).toArray)
    }
    relevantDocuments.cache()

    // Instantiate metrics object
    val metrics = new RankingMetrics(relevantDocuments)

    val metricResults : Map[String, Double] = Map(
      "Precision at 1" -> metrics.precisionAt(1),
      "Precision at 5" -> metrics.precisionAt(5),
      "Precision at 10" -> metrics.precisionAt(10),
      "MAP" -> metrics.meanAveragePrecision,
      "NDCG at 1" -> metrics.ndcgAt(1),
      "NDCG at 5" -> metrics.ndcgAt(5),
      "NDCG at 10" -> metrics.ndcgAt(10)
    )

    logger.info(s"Training metrics ${metricResults}.")

    new ALSModel(
      rank = m.rank,
      userFeatures = m.userFeatures.collectAsMap.toMap,
      productFeatures = m.productFeatures.collectAsMap.toMap,
      userStringIntMap = userStringIntMap,
      itemStringIntMap = itemStringIntMap
    )
  }

  def predict(model: ALSModel, query: Query): PredictedResult = {

    val itemStringIntMap = model.itemStringIntMap
    val productFeatures = model.productFeatures

    // default itemScores array if items are not ranked at all
    lazy val notRankedItemScores =
      query.items.map(i => ItemScore(i, 0)).toArray

    model.userStringIntMap.get(query.user).map { userIndex =>
      // lookup userFeature for the user
      model.userFeatures.get(userIndex)
    }.flatten // flatten Option[Option[Array[Double]]] to Option[Array[Double]]
    .map { userFeature =>
      val scores: Vector[Option[Double]] = query.items.toVector
        .par // convert to parallel collection for parallel lookup
        .map { iid =>
          // convert query item id to index
          val featureOpt: Option[Array[Double]] = itemStringIntMap.get(iid)
            // productFeatures may not contain the item
            .map (index => productFeatures.get(index))
            // flatten Option[Option[Array[Double]]] to Option[Array[Double]]
            .flatten

          featureOpt.map(f => dotProduct(f, userFeature))
        }.seq // convert back to sequential collection

      // check if all scores is None (get rid of all None and see if empty)
      val isAllNone = scores.flatten.isEmpty

      if (isAllNone) {
        logger.info(s"No productFeature for all items ${query.items}.")
        PredictedResult(
          itemScores = notRankedItemScores,
          isOriginal = true
        )
      } else {
        // sort the score
        val ord = Ordering.by[ItemScore, Double](_.score).reverse
        val sorted = scores.map{s =>
            val score = s.getOrElse[Double](0)
            if (score >= 0) score else 0d // zero out negative scores
          }.zip(query.items).map{ case (s, iid) =>
            ItemScore(
              item = iid,
              score = s
            )
          }.sorted(ord).toArray

        PredictedResult(
          itemScores = sorted,
          isOriginal = false
        )
      }
    }.getOrElse {
      logger.info(s"No userFeature found for user ${query.user}.")
      PredictedResult(
        itemScores = notRankedItemScores,
        isOriginal = true
      )
    }

  }

  private
  def dotProduct(v1: Array[Double], v2: Array[Double]): Double = {
    val size = v1.size
    var i = 0
    var d: Double = 0
    while (i < size) {
      d += v1(i) * v2(i)
      i += 1
    }
    d
  }

}
