/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.recommendation

import java.io.File
import java.util.Random

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.WrappedArray
import scala.language.existentials

import com.github.fommil.netlib.BLAS.{getInstance => blas}
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.TrueFileFilter
import org.scalatest.BeforeAndAfterEach

import org.apache.spark._
import org.apache.spark.internal.Logging
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.recommendation.ALS._
import org.apache.spark.ml.recommendation.ALS.Rating
import org.apache.spark.ml.util.{DefaultReadWriteTest, MLTestingUtils}
import org.apache.spark.ml.util.TestingUtils._
import org.apache.spark.mllib.recommendation.MatrixFactorizationModelSuite
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.scheduler.{SparkListener, SparkListenerStageCompleted}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

class ALSSuite
  extends SparkFunSuite with MLlibTestSparkContext with DefaultReadWriteTest with Logging {

  override def beforeAll(): Unit = {
    super.beforeAll()
    sc.setCheckpointDir(tempDir.getAbsolutePath)
  }

  override def afterAll(): Unit = {
    super.afterAll()
  }

  test("LocalIndexEncoder") {
    val random = new Random
    for (numBlocks <- Seq(1, 2, 5, 10, 20, 50, 100)) {
      val encoder = new LocalIndexEncoder(numBlocks)
      val maxLocalIndex = Int.MaxValue / numBlocks
      val tests = Seq.fill(5)((random.nextInt(numBlocks), random.nextInt(maxLocalIndex))) ++
        Seq((0, 0), (numBlocks - 1, maxLocalIndex))
      tests.foreach { case (blockId, localIndex) =>
        val err = s"Failed with numBlocks=$numBlocks, blockId=$blockId, and localIndex=$localIndex."
        val encoded = encoder.encode(blockId, localIndex)
        assert(encoder.blockId(encoded) === blockId, err)
        assert(encoder.localIndex(encoded) === localIndex, err)
      }
    }
  }
  //正态方程构造
  test("normal equation construction") {
    val k = 2
    val ne0 = new NormalEquation(k)
      .add(Array(1.0f, 2.0f), 3.0)
      .add(Array(4.0f, 5.0f), 12.0, 2.0) // weighted
    assert(ne0.k === k)
    assert(ne0.triK === k * (k + 1) / 2)
    // NumPy code that computes the expected values:
    // A = np.matrix("1 2; 4 5")
    // b = np.matrix("3; 6")
    // C = np.matrix(np.diag([1, 2]))
    // ata = A.transpose() * C * A
    // atb = A.transpose() * C * b
    assert(Vectors.dense(ne0.ata) ~== Vectors.dense(33.0, 42.0, 54.0) relTol 1e-8)
    assert(Vectors.dense(ne0.atb) ~== Vectors.dense(51.0, 66.0) relTol 1e-8)

    val ne1 = new NormalEquation(2)
      .add(Array(7.0f, 8.0f), 9.0)
    ne0.merge(ne1)
    // NumPy code that computes the expected values:
    // A = np.matrix("1 2; 4 5; 7 8")
    // b = np.matrix("3; 6; 9")
    // C = np.matrix(np.diag([1, 2, 1]))
    // ata = A.transpose() * C * A
    // atb = A.transpose() * C * b
    assert(Vectors.dense(ne0.ata) ~== Vectors.dense(82.0, 98.0, 118.0) relTol 1e-8)
    assert(Vectors.dense(ne0.atb) ~== Vectors.dense(114.0, 138.0) relTol 1e-8)

    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f), 2.0)
    }
    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f, 2.0f, 3.0f), 4.0)
    }
    intercept[IllegalArgumentException] {
      ne0.add(Array(1.0f, 2.0f), 0.0, -1.0)
    }
    intercept[IllegalArgumentException] {
      val ne2 = new NormalEquation(3)
      ne0.merge(ne2)
    }

    ne0.reset()
    assert(ne0.ata.forall(_ == 0.0))
    assert(ne0.atb.forall(_ == 0.0))
  }

  test("CholeskySolver") {
    val k = 2
    val ne0 = new NormalEquation(k)
      .add(Array(1.0f, 2.0f), 4.0)
      .add(Array(1.0f, 3.0f), 9.0)
      .add(Array(1.0f, 4.0f), 16.0)
    val ne1 = new NormalEquation(k)
      .merge(ne0)

    val chol = new CholeskySolver
    val x0 = chol.solve(ne0, 0.0).map(_.toDouble)
    // NumPy code that computes the expected solution:
    //NumPy码计算预期的解决方案：
    // A = np.matrix("1 2; 1 3; 1 4")
    // b = b = np.matrix("3; 6")
    // x0 = np.linalg.lstsq(A, b)[0]
    assert(Vectors.dense(x0) ~== Vectors.dense(-8.333333, 6.0) relTol 1e-6)

    assert(ne0.ata.forall(_ == 0.0))
    assert(ne0.atb.forall(_ == 0.0))

    val x1 = chol.solve(ne1, 1.5).map(_.toDouble)
    // NumPy code that computes the expected solution, where lambda is scaled by n:
    // x0 = np.linalg.solve(A.transpose() * A + 1.5 * np.eye(2), A.transpose() * b)
    assert(Vectors.dense(x1) ~== Vectors.dense(-0.1155556, 3.28) relTol 1e-6)
  }
  //评级模块生成器
  test("RatingBlockBuilder") {
    val emptyBuilder = new RatingBlockBuilder[Int]()
    assert(emptyBuilder.size === 0)
    val emptyBlock = emptyBuilder.build()
    assert(emptyBlock.srcIds.isEmpty)
    assert(emptyBlock.dstIds.isEmpty)
    assert(emptyBlock.ratings.isEmpty)

    val builder0 = new RatingBlockBuilder()
      .add(Rating(0, 1, 2.0f))
      .add(Rating(3, 4, 5.0f))
    assert(builder0.size === 2)
    val builder1 = new RatingBlockBuilder()
      .add(Rating(6, 7, 8.0f))
      .merge(builder0.build())
    assert(builder1.size === 3)
    val block = builder1.build()
    val ratings = Seq.tabulate(block.size) { i =>
      (block.srcIds(i), block.dstIds(i), block.ratings(i))
    }.toSet
    assert(ratings === Set((0, 1, 2.0f), (3, 4, 5.0f), (6, 7, 8.0f)))
  }
  //未经压缩的块
  test("UncompressedInBlock") {
    val encoder = new LocalIndexEncoder(10)
    val uncompressed = new UncompressedInBlockBuilder[Int](encoder)
      .add(0, Array(1, 0, 2), Array(0, 1, 4), Array(1.0f, 2.0f, 3.0f))
      .add(1, Array(3, 0), Array(2, 5), Array(4.0f, 5.0f))
      .build()
    assert(uncompressed.length === 5)
    val records = Seq.tabulate(uncompressed.length) { i =>
      val dstEncodedIndex = uncompressed.dstEncodedIndices(i)
      val dstBlockId = encoder.blockId(dstEncodedIndex)
      val dstLocalIndex = encoder.localIndex(dstEncodedIndex)
      (uncompressed.srcIds(i), dstBlockId, dstLocalIndex, uncompressed.ratings(i))
    }.toSet
    val expected =
      Set((1, 0, 0, 1.0f), (0, 0, 1, 2.0f), (2, 0, 4, 3.0f), (3, 1, 2, 4.0f), (0, 1, 5, 5.0f))
    assert(records === expected)

    val compressed = uncompressed.compress()
    assert(compressed.size === 5)
    assert(compressed.srcIds.toSeq === Seq(0, 1, 2, 3))
    assert(compressed.dstPtrs.toSeq === Seq(0, 2, 3, 4, 5))
    var decompressed = ArrayBuffer.empty[(Int, Int, Int, Float)]
    var i = 0
    while (i < compressed.srcIds.length) {
      var j = compressed.dstPtrs(i)
      while (j < compressed.dstPtrs(i + 1)) {
        val dstEncodedIndex = compressed.dstEncodedIndices(j)
        val dstBlockId = encoder.blockId(dstEncodedIndex)
        val dstLocalIndex = encoder.localIndex(dstEncodedIndex)
        decompressed += ((compressed.srcIds(i), dstBlockId, dstLocalIndex, compressed.ratings(j)))
        j += 1
      }
      i += 1
    }
    assert(decompressed.toSet === expected)
  }
  //如果安全值超出整数范围或包含小数部分,则安全地将用户/项目ID转换为int会引发异常。
  test("CheckedCast") {
    val checkedCast = new ALS().checkedCast
    val df = spark.range(1)
    //有效的整数ID
    withClue("Valid Integer Ids") {
      df.select(checkedCast(lit(123))).collect()
    }
    //有效的Long ID
    withClue("Valid Long Ids") {
      df.select(checkedCast(lit(1231L))).collect()
    }
    //有效的十进制ID
    withClue("Valid Decimal Ids") {
      df.select(checkedCast(lit(123).cast(DecimalType(15, 2)))).collect()
    }
    //有效的Double ID
    withClue("Valid Double Ids") {
      df.select(checkedCast(lit(123.0))).collect()
    }
    //在整数范围外或包含小数部分
    val msg = "either out of Integer range or contained a fractional part"
    //无效的长：超出范围
    withClue("Invalid Long: out of range") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit(1231000000000L))).collect()
      }
      assert(e.getMessage.contains(msg))
    }
    //十进制无效：超出范围
    withClue("Invalid Decimal: out of range") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit(1231000000000.0).cast(DecimalType(15, 2)))).collect()
      }
      assert(e.getMessage.contains(msg))
    }
    //十进制无效：小数部分
    withClue("Invalid Decimal: fractional part") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit(123.1).cast(DecimalType(15, 2)))).collect()
      }
      assert(e.getMessage.contains(msg))
    }
    //无效的Double：超出范围
    withClue("Invalid Double: out of range") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit(1231000000000.0))).collect()
      }
      assert(e.getMessage.contains(msg))
    }
    //无效Double：小数部分
    withClue("Invalid Double: fractional part") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit(123.1))).collect()
      }
      assert(e.getMessage.contains(msg))
    }
    //无效的类型
    withClue("Invalid Type") {
      val e: SparkException = intercept[SparkException] {
        df.select(checkedCast(lit("123.1"))).collect()
      }
      assert(e.getMessage.contains("was not numeric"))
    }
  }

  /**
   * Generates an explicit feedback dataset for testing ALS.
    * 生成用于测试ALS的显式反馈数据集
   * @param numUsers number of users
   * @param numItems number of items
   * @param rank rank
   * @param noiseStd the standard deviation of additive Gaussian noise on training data
    *                 加性高斯噪声对训练数据的标准差
    * @param seed random seed
   * @return (training, test)
   */
  def genExplicitTestData(
      numUsers: Int,
      numItems: Int,
      rank: Int,
      noiseStd: Double = 0.0,
      seed: Long = 11L): (RDD[Rating[Int]], RDD[Rating[Int]]) = {
    val trainingFraction = 0.6
    val testFraction = 0.3
    val totalFraction = trainingFraction + testFraction
    val random = new Random(seed)
    val userFactors = genFactors(numUsers, rank, random)
    val itemFactors = genFactors(numItems, rank, random)
    val training = ArrayBuffer.empty[Rating[Int]]
    val test = ArrayBuffer.empty[Rating[Int]]
    for ((userId, userFactor) <- userFactors; (itemId, itemFactor) <- itemFactors) {
      val x = random.nextDouble()
      if (x < totalFraction) {
        val rating = blas.sdot(rank, userFactor, 1, itemFactor, 1)
        if (x < trainingFraction) {
          val noise = noiseStd * random.nextGaussian()
          training += Rating(userId, itemId, rating + noise.toFloat)
        } else {
          test += Rating(userId, itemId, rating)
        }
      }
    }
    logInfo(s"Generated an explicit feedback dataset with ${training.size} ratings for training " +
      s"and ${test.size} for test.")
    (sc.parallelize(training, 2), sc.parallelize(test, 2))
  }

  /**
   * Generates an implicit feedback dataset for testing ALS.
    * 生成用于测试ALS的隐式反馈数据集
   * @param numUsers number of users
   * @param numItems number of items
   * @param rank rank
   * @param noiseStd the standard deviation of additive Gaussian noise on training data
   * @param seed random seed
   * @return (training, test)
   */
  def genImplicitTestData(
      numUsers: Int,
      numItems: Int,
      rank: Int,
      noiseStd: Double = 0.0,
      seed: Long = 11L): (RDD[Rating[Int]], RDD[Rating[Int]]) = {
    ALSSuite.genImplicitTestData(sc, numUsers, numItems, rank, noiseStd, seed)
  }

  /**
   * Generates random user/item factors, with i.i.d. values drawn from U(a, b).
    * 使用i.i.d生成随机用户/项目因子。 从U（a，b）中抽取的值
   * @param size number of users/items
   * @param rank number of features
   * @param random random number generator
   * @param a min value of the support (default: -1)
   * @param b max value of the support (default: 1)
   * @return a sequence of (ID, factors) pairs
   */
  private def genFactors(
      size: Int,
      rank: Int,
      random: Random,
      a: Float = -1.0f,
      b: Float = 1.0f): Seq[(Int, Array[Float])] = {
    ALSSuite.genFactors(size, rank, random, a, b)
  }

  /**
  * Train ALS using the given training set and parameters
    * 使用给定的训练集和参数训练ALS
  * @param training training dataset
  * @param rank rank of the matrix factorization 矩阵分解的秩
  * @param maxIter max number of iterations 最大迭代次数
  * @param regParam regularization constant 正则化常数
  * @param implicitPrefs whether to use implicit preference 是否使用隐式偏好
  * @param numUserBlocks number of user blocks
  * @param numItemBlocks number of item blocks
  * @return a trained ALSModel
  */
  def trainALS(
    training: RDD[Rating[Int]],
    rank: Int,
    maxIter: Int,
    regParam: Double,
    implicitPrefs: Boolean = false,
    numUserBlocks: Int = 2,
    numItemBlocks: Int = 3): ALSModel = {
    val spark = this.spark
    import spark.implicits._
    val als = new ALS()
      .setRank(rank)
      .setRegParam(regParam)
      .setImplicitPrefs(implicitPrefs)
      .setNumUserBlocks(numUserBlocks)
      .setNumItemBlocks(numItemBlocks)
      .setSeed(0)
    als.fit(training.toDF())
  }

  /**
   * Test ALS using the given training/test splits and parameters.
    * 使用给定的训练/测试分组和参数测试ALS
   * @param training training dataset
   * @param test test dataset
   * @param rank rank of the matrix factorization
   * @param maxIter max number of iterations
   * @param regParam regularization constant
   * @param implicitPrefs whether to use implicit preference
   * @param numUserBlocks number of user blocks
   * @param numItemBlocks number of item blocks
   * @param targetRMSE target test RMSE
   */
  def testALS(
      training: RDD[Rating[Int]],
      test: RDD[Rating[Int]],
      rank: Int,
      maxIter: Int,
      regParam: Double,
      implicitPrefs: Boolean = false,
      numUserBlocks: Int = 2,
      numItemBlocks: Int = 3,
      targetRMSE: Double = 0.05): Unit = {
    val spark = this.spark
    import spark.implicits._
    val als = new ALS()
      .setRank(rank)
      .setRegParam(regParam)
      .setImplicitPrefs(implicitPrefs)
      .setNumUserBlocks(numUserBlocks)
      .setNumItemBlocks(numItemBlocks)
      .setSeed(0)
    val alpha = als.getAlpha
    val model = als.fit(training.toDF())
    val predictions = model.transform(test.toDF()).select("rating", "prediction").rdd.map {
      case Row(rating: Float, prediction: Float) =>
        (rating.toDouble, prediction.toDouble)
    }
    val rmse =
      if (implicitPrefs) {
        // TODO: Use a better (rank-based?) evaluation metric for implicit feedback.
        // We limit the ratings and the predictions to interval [0, 1] and compute the weighted RMSE
        // with the confidence scores as weights.
        val (totalWeight, weightedSumSq) = predictions.map { case (rating, prediction) =>
          val confidence = 1.0 + alpha * math.abs(rating)
          val rating01 = math.max(math.min(rating, 1.0), 0.0)
          val prediction01 = math.max(math.min(prediction, 1.0), 0.0)
          val err = prediction01 - rating01
          (confidence, confidence * err * err)
        }.reduce { case ((c0, e0), (c1, e1)) =>
          (c0 + c1, e0 + e1)
        }
        math.sqrt(weightedSumSq / totalWeight)
      } else {
        val mse = predictions.map { case (rating, prediction) =>
          val err = rating - prediction
          err * err
        }.mean()
        math.sqrt(mse)
      }
    logInfo(s"Test RMSE is $rmse.")
    assert(rmse < targetRMSE)

    MLTestingUtils.checkCopyAndUids(als, model)
  }
  //确切的秩-1矩阵
  test("exact rank-1 matrix") {
    val (training, test) = genExplicitTestData(numUsers = 20, numItems = 40, rank = 1)
    testALS(training, test, maxIter = 1, rank = 1, regParam = 1e-5, targetRMSE = 0.001)
    testALS(training, test, maxIter = 1, rank = 2, regParam = 1e-5, targetRMSE = 0.001)
  }
  //近似等级1的矩阵
  test("approximate rank-1 matrix") {
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 1, noiseStd = 0.01)
    testALS(training, test, maxIter = 2, rank = 1, regParam = 0.01, targetRMSE = 0.02)
    testALS(training, test, maxIter = 2, rank = 2, regParam = 0.01, targetRMSE = 0.02)
  }
  //近似等级2的矩阵
  test("approximate rank-2 matrix") {
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    testALS(training, test, maxIter = 4, rank = 2, regParam = 0.01, targetRMSE = 0.03)
    testALS(training, test, maxIter = 4, rank = 3, regParam = 0.01, targetRMSE = 0.03)
  }
  //不同的块设置
  test("different block settings") {
    val (training, test) =
      genExplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    for ((numUserBlocks, numItemBlocks) <- Seq((1, 1), (1, 2), (2, 1), (2, 2))) {
      testALS(training, test, maxIter = 4, rank = 3, regParam = 0.01, targetRMSE = 0.03,
        numUserBlocks = numUserBlocks, numItemBlocks = numItemBlocks)
    }
  }
  //比评分更多的块
  test("more blocks than ratings") {
    val (training, test) =
      genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)
    testALS(training, test, maxIter = 2, rank = 1, regParam = 1e-4, targetRMSE = 0.002,
     numItemBlocks = 5, numUserBlocks = 5)
  }
  //隐式反馈
  test("implicit feedback") {
    val (training, test) =
      genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    testALS(training, test, maxIter = 4, rank = 2, regParam = 0.01, implicitPrefs = true,
      targetRMSE = 0.3)
  }
  //隐式反馈回归
  test("implicit feedback regression") {
    val trainingWithNeg = sc.parallelize(Array(Rating(0, 0, 1), Rating(1, 1, 1), Rating(0, 1, -3)))
    val trainingWithZero = sc.parallelize(Array(Rating(0, 0, 1), Rating(1, 1, 1), Rating(0, 1, 0)))
    val modelWithNeg =
      trainALS(trainingWithNeg, rank = 1, maxIter = 5, regParam = 0.01, implicitPrefs = true)
    val modelWithZero =
      trainALS(trainingWithZero, rank = 1, maxIter = 5, regParam = 0.01, implicitPrefs = true)
    val userFactorsNeg = modelWithNeg.userFactors
    val itemFactorsNeg = modelWithNeg.itemFactors
    val userFactorsZero = modelWithZero.userFactors
    val itemFactorsZero = modelWithZero.itemFactors
    assert(userFactorsNeg.intersect(userFactorsZero).count() == 0)
    assert(itemFactorsNeg.intersect(itemFactorsZero).count() == 0)
  }
  //使用通用的ID类型
  test("using generic ID types") {
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)

    val longRatings = ratings.map(r => Rating(r.user.toLong, r.item.toLong, r.rating))
    val (longUserFactors, _) = ALS.train(longRatings, rank = 2, maxIter = 4, seed = 0)
    assert(longUserFactors.first()._1.getClass === classOf[Long])

    val strRatings = ratings.map(r => Rating(r.user.toString, r.item.toString, r.rating))
    val (strUserFactors, _) = ALS.train(strRatings, rank = 2, maxIter = 4, seed = 0)
    assert(strUserFactors.first()._1.getClass === classOf[String])
  }
  //非负约束
  test("nonnegative constraint") {
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    val (userFactors, itemFactors) =
      ALS.train(ratings, rank = 2, maxIter = 4, nonnegative = true, seed = 0)
    def isNonnegative(factors: RDD[(Int, Array[Float])]): Boolean = {
      factors.values.map { _.forall(_ >= 0.0) }.reduce(_ && _)
    }
    assert(isNonnegative(userFactors))
    assert(isNonnegative(itemFactors))
    // TODO: Validate the solution.
  }
  //als分区器是一个projection
  test("als partitioner is a projection") {
    for (p <- Seq(1, 10, 100, 1000)) {
      val part = new ALSPartitioner(p)
      var k = 0
      while (k < p) {
        assert(k === part.getPartition(k))
        assert(k === part.getPartition(k.toLong))
        k += 1
      }
    }
  }
  //在返回的因素分区
  test("partitioner in returned factors") {
    val (ratings, _) = genImplicitTestData(numUsers = 20, numItems = 40, rank = 2, noiseStd = 0.01)
    val (userFactors, itemFactors) = ALS.train(
      ratings, rank = 2, maxIter = 4, numUserBlocks = 3, numItemBlocks = 4, seed = 0)
    for ((tpe, factors) <- Seq(("User", userFactors), ("Item", itemFactors))) {
      assert(userFactors.partitioner.isDefined, s"$tpe factors should have partitioner.")
      val part = userFactors.partitioner.get
      userFactors.mapPartitionsWithIndex { (idx, items) =>
        items.foreach { case (id, _) =>
          if (part.getPartition(id) != idx) {
            throw new SparkException(s"$tpe with ID $id should not be in partition $idx.")
          }
        }
        Iterator.empty
      }.count()
    }
  }
  //具有大量迭代的als
  test("als with large number of iterations") {
    val (ratings, _) = genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)
    ALS.train(ratings, rank = 1, maxIter = 50, numUserBlocks = 2, numItemBlocks = 2, seed = 0)
    ALS.train(ratings, rank = 1, maxIter = 50, numUserBlocks = 2, numItemBlocks = 2,
      implicitPrefs = true, seed = 0)
  }

  test("read/write") {
    val spark = this.spark
    import spark.implicits._
    import ALSSuite._
    val (ratings, _) = genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)

    def getFactors(df: DataFrame): Set[(Int, Array[Float])] = {
      df.select("id", "features").collect().map { case r =>
        (r.getInt(0), r.getAs[Array[Float]](1))
      }.toSet
    }

    def checkModelData(model: ALSModel, model2: ALSModel): Unit = {
      assert(model.rank === model2.rank)
      assert(getFactors(model.userFactors) === getFactors(model2.userFactors))
      assert(getFactors(model.itemFactors) === getFactors(model2.itemFactors))
    }

    val als = new ALS()
    testEstimatorAndModelReadWrite(als, ratings.toDF(), allEstimatorParamSettings,
      allModelParamSettings, checkModelData)
  }
  //输入类型验证
  test("input type validation") {
    val spark = this.spark
    import spark.implicits._

    // check that ALS can handle all numeric types for rating column
    // and user/item columns (when the user/item ids are within Int range)
    val als = new ALS().setMaxIter(1).setRank(1)
    Seq(("user", IntegerType), ("item", IntegerType), ("rating", FloatType)).foreach {
      case (colName, sqlType) =>
        MLTestingUtils.checkNumericTypesALS(als, spark, colName, sqlType) {
          (ex, act) =>
            ex.userFactors.first().getSeq[Float](1) === act.userFactors.first.getSeq[Float](1)
        } { (ex, act, _) =>
          ex.transform(_: DataFrame).select("prediction").first.getDouble(0) ~==
            act.transform(_: DataFrame).select("prediction").first.getDouble(0) absTol 1e-6
        }
    }
    // check user/item ids falling outside of Int range
    val big = Int.MaxValue.toLong + 1
    val small = Int.MinValue.toDouble - 1
    val df = Seq(
      (0, 0L, 0d, 1, 1L, 1d, 3.0),
      (0, big, small, 0, big, small, 2.0),
      (1, 1L, 1d, 0, 0L, 0d, 5.0)
    ).toDF("user", "user_big", "user_small", "item", "item_big", "item_small", "rating")
    val msg = "either out of Integer range or contained a fractional part"
    withClue("fit should fail when ids exceed integer range. ") {
      assert(intercept[SparkException] {
        als.fit(df.select(df("user_big").as("user"), df("item"), df("rating")))
      }.getCause.getMessage.contains(msg))
      assert(intercept[SparkException] {
        als.fit(df.select(df("user_small").as("user"), df("item"), df("rating")))
      }.getCause.getMessage.contains(msg))
      assert(intercept[SparkException] {
        als.fit(df.select(df("item_big").as("item"), df("user"), df("rating")))
      }.getCause.getMessage.contains(msg))
      assert(intercept[SparkException] {
        als.fit(df.select(df("item_small").as("item"), df("user"), df("rating")))
      }.getCause.getMessage.contains(msg))
    }
    //当id超过整数范围时，转换将失败
    withClue("transform should fail when ids exceed integer range. ") {
      val model = als.fit(df)
      assert(intercept[SparkException] {
        model.transform(df.select(df("user_big").as("user"), df("item"))).first
      }.getMessage.contains(msg))
      assert(intercept[SparkException] {
        model.transform(df.select(df("user_small").as("user"), df("item"))).first
      }.getMessage.contains(msg))
      assert(intercept[SparkException] {
        model.transform(df.select(df("item_big").as("item"), df("user"))).first
      }.getMessage.contains(msg))
      assert(intercept[SparkException] {
        model.transform(df.select(df("item_small").as("item"), df("user"))).first
      }.getMessage.contains(msg))
    }
  }
  //具有空RDD的ALS应该会失败并显示更好的消息
  test("SPARK-18268: ALS with empty RDD should fail with better message") {
    val ratings = sc.parallelize(Array.empty[Rating[Int]])
    intercept[IllegalArgumentException] {
      ALS.train(ratings)
    }
  }
  //ALS冷启动用户/项目预测策略
  test("ALS cold start user/item prediction strategy") {
    val spark = this.spark
    import spark.implicits._
    import org.apache.spark.sql.functions._

    val (ratings, _) = genExplicitTestData(numUsers = 4, numItems = 4, rank = 1)
    val data = ratings.toDF
    val knownUser = data.select(max("user")).as[Int].first()
    val unknownUser = knownUser + 10
    val knownItem = data.select(max("item")).as[Int].first()
    val unknownItem = knownItem + 20
    val test = Seq(
      (unknownUser, unknownItem),
      (knownUser, unknownItem),
      (unknownUser, knownItem),
      (knownUser, knownItem)
    ).toDF("user", "item")

    val als = new ALS().setMaxIter(1).setRank(1)
    // default is 'nan'
    val defaultModel = als.fit(data)
    val defaultPredictions = defaultModel.transform(test).select("prediction").as[Float].collect()
    assert(defaultPredictions.length == 4)
    assert(defaultPredictions.slice(0, 3).forall(_.isNaN))
    assert(!defaultPredictions.last.isNaN)

    // check 'drop' strategy should filter out rows with unknown users/items
    //检查“下降”策略应该过滤掉具有未知用户/项目的行
    val dropPredictions = defaultModel
      .setColdStartStrategy("drop")
      .transform(test)
      .select("prediction").as[Float].collect()
    assert(dropPredictions.length == 1)
    assert(!dropPredictions.head.isNaN)
    assert(dropPredictions.head ~== defaultPredictions.last relTol 1e-14)
  }
  //不区分大小写的冷启动参数值
  test("case insensitive cold start param value") {
    val spark = this.spark
    import spark.implicits._
    val (ratings, _) = genExplicitTestData(numUsers = 2, numItems = 2, rank = 1)
    val data = ratings.toDF
    val model = new ALS().fit(data)
    Seq("nan", "NaN", "Nan", "drop", "DROP", "Drop").foreach { s =>
      model.setColdStartStrategy(s).transform(data)
    }
  }

  private def getALSModel = {
    val spark = this.spark
    import spark.implicits._

    val userFactors = Seq(
      (0, Array(6.0f, 4.0f)),
      (1, Array(3.0f, 4.0f)),
      (2, Array(3.0f, 6.0f))
    ).toDF("id", "features")
    val itemFactors = Seq(
      (3, Array(5.0f, 6.0f)),
      (4, Array(6.0f, 2.0f)),
      (5, Array(3.0f, 6.0f)),
      (6, Array(4.0f, 1.0f))
    ).toDF("id", "features")
    val als = new ALS().setRank(2)
    new ALSModel(als.uid, als.getRank, userFactors, itemFactors)
      .setUserCol("user")
      .setItemCol("item")
  }
  //推荐使用k <，=和> num_items的所有用户
  test("recommendForAllUsers with k <, = and > num_items") {
    val model = getALSModel
    val numUsers = model.userFactors.count
    val numItems = model.itemFactors.count
    val expected = Map(
      0 -> Array((3, 54f), (4, 44f), (5, 42f), (6, 28f)),
      1 -> Array((3, 39f), (5, 33f), (4, 26f), (6, 16f)),
      2 -> Array((3, 51f), (5, 45f), (4, 30f), (6, 18f))
    )

    Seq(2, 4, 6).foreach { k =>
      val n = math.min(k, numItems).toInt
      val expectedUpToN = expected.mapValues(_.slice(0, n))
      val topItems = model.recommendForAllUsers(k)
      assert(topItems.count() == numUsers)
      assert(topItems.columns.contains("user"))
      checkRecommendations(topItems, expectedUpToN, "item")
    }
  }
  //带有k <，=和> num_users的recommendForAllItems
  test("recommendForAllItems with k <, = and > num_users") {
    val model = getALSModel
    val numUsers = model.userFactors.count
    val numItems = model.itemFactors.count
    val expected = Map(
      3 -> Array((0, 54f), (2, 51f), (1, 39f)),
      4 -> Array((0, 44f), (2, 30f), (1, 26f)),
      5 -> Array((2, 45f), (0, 42f), (1, 33f)),
      6 -> Array((0, 28f), (2, 18f), (1, 16f))
    )

    Seq(2, 3, 4).foreach { k =>
      val n = math.min(k, numUsers).toInt
      val expectedUpToN = expected.mapValues(_.slice(0, n))
      val topUsers = getALSModel.recommendForAllItems(k)
      assert(topUsers.count() == numItems)
      assert(topUsers.columns.contains("item"))
      checkRecommendations(topUsers, expectedUpToN, "user")
    }
  }

  private def checkRecommendations(
      topK: DataFrame,
      expected: Map[Int, Array[(Int, Float)]],
      dstColName: String): Unit = {
    val spark = this.spark
    import spark.implicits._

    assert(topK.columns.contains("recommendations"))
    topK.as[(Int, Seq[(Int, Float)])].collect().foreach { case (id: Int, recs: Seq[(Int, Float)]) =>
      assert(recs === expected(id))
    }
    topK.collect().foreach { row =>
      val recs = row.getAs[WrappedArray[Row]]("recommendations")
      assert(recs(0).fieldIndex(dstColName) == 0)
      assert(recs(0).fieldIndex("rating") == 1)
    }
  }
}

class ALSCleanerSuite extends SparkFunSuite with BeforeAndAfterEach {
  override def beforeEach(): Unit = {
    super.beforeEach()
    // Once `Utils.getOrCreateLocalRootDirs` is called, it is cached in `Utils.localRootDirs`.
    // Unless this is manually cleared before and after a test, it returns the same directory
    // set before even if 'spark.local.dir' is configured afterwards.
    Utils.clearLocalRootDirs()
  }

  override def afterEach(): Unit = {
    Utils.clearLocalRootDirs()
    super.afterEach()
  }
  //ALS shuffle清理独立
  test("ALS shuffle cleanup standalone") {
    val conf = new SparkConf()
    val localDir = Utils.createTempDir()
    val checkpointDir = Utils.createTempDir()
    def getAllFiles: Set[File] =
      FileUtils.listFiles(localDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE).asScala.toSet
    try {
      conf.set("spark.local.dir", localDir.getAbsolutePath)
      val sc = new SparkContext("local[2]", "test", conf)
      try {
        sc.setCheckpointDir(checkpointDir.getAbsolutePath)
        // Test checkpoint and clean parents
        val input = sc.parallelize(1 to 1000)
        val keyed = input.map(x => (x % 20, 1))
        val shuffled = keyed.reduceByKey(_ + _)
        val keysOnly = shuffled.keys
        val deps = keysOnly.dependencies
        keysOnly.count()
        ALS.cleanShuffleDependencies(sc, deps, true)
        val resultingFiles = getAllFiles
        assert(resultingFiles === Set())
        // Ensure running count again works fine even if we kill the shuffle files.
        //确保运行计数再次正常工作,即使我们杀死了洗牌文件
        keysOnly.count()
      } finally {
        sc.stop()
      }
    } finally {
      Utils.deleteRecursively(localDir)
      Utils.deleteRecursively(checkpointDir)
    }
  }
  //算法中的ALS shuffle清理
  test("ALS shuffle cleanup in algorithm") {
    val conf = new SparkConf()
    val localDir = Utils.createTempDir()
    val checkpointDir = Utils.createTempDir()
    def getAllFiles: Set[File] =
      FileUtils.listFiles(localDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE).asScala.toSet
    try {
      conf.set("spark.local.dir", localDir.getAbsolutePath)
      val sc = new SparkContext("local[2]", "ALSCleanerSuite", conf)
      try {
        sc.setCheckpointDir(checkpointDir.getAbsolutePath)
        // Generate test data
        val (training, _) = ALSSuite.genImplicitTestData(sc, 20, 5, 1, 0.2, 0)
        // Implicitly test the cleaning of parents during ALS training
        //在ALS培训期间隐式测试父的清洁
        val spark = SparkSession.builder
          .sparkContext(sc)
          .getOrCreate()
        import spark.implicits._
        val als = new ALS()
          .setRank(1)
          .setRegParam(1e-5)
          .setSeed(0)
          .setCheckpointInterval(1)
          .setMaxIter(7)
        val model = als.fit(training.toDF())
        val resultingFiles = getAllFiles
        // We expect the last shuffles files, block ratings, user factors, and item factors to be
        // around but no more.
        //我们预计最后的洗牌文件,数据块评级,用户因素和项目因素将在周围,但不会超过
        val pattern = "shuffle_(\\d+)_.+\\.data".r
        val rddIds = resultingFiles.flatMap { f =>
          pattern.findAllIn(f.getName()).matchData.map { _.group(1) } }
        assert(rddIds.size === 4)
      } finally {
        sc.stop()
      }
    } finally {
      Utils.deleteRecursively(localDir)
      Utils.deleteRecursively(checkpointDir)
    }
  }
}

class ALSStorageSuite
  extends SparkFunSuite with MLlibTestSparkContext with DefaultReadWriteTest with Logging {
  //无效的存储参数
  test("invalid storage params") {
    intercept[IllegalArgumentException] {
      new ALS().setIntermediateStorageLevel("foo")
    }
    intercept[IllegalArgumentException] {
      new ALS().setIntermediateStorageLevel("NONE")
    }
    intercept[IllegalArgumentException] {
      new ALS().setFinalStorageLevel("foo")
    }
  }
  //默认和非默认存储参数设置正确的RDD StorageLevels
  test("default and non-default storage params set correct RDD StorageLevels") {
    val spark = this.spark
    import spark.implicits._
    val data = Seq(
      (0, 0, 1.0),
      (0, 1, 2.0),
      (1, 2, 3.0),
      (1, 0, 2.0)
    ).toDF("user", "item", "rating")
    val als = new ALS().setMaxIter(1).setRank(1)
    // add listener to check intermediate RDD default storage levels
    //添加监听器来检查中间RDD默认存储级别
    val defaultListener = new IntermediateRDDStorageListener
    sc.addSparkListener(defaultListener)
    val model = als.fit(data)
    // check final factor RDD default storage levels
    //检查最终因子RDD默认存储级别
    val defaultFactorRDDs = sc.getPersistentRDDs.collect {
      case (id, rdd) if rdd.name == "userFactors" || rdd.name == "itemFactors" =>
        rdd.name -> ((id, rdd.getStorageLevel))
    }.toMap
    defaultFactorRDDs.foreach { case (_, (id, level)) =>
      assert(level == StorageLevel.MEMORY_AND_DISK)
    }
    defaultListener.storageLevels.foreach(level => assert(level == StorageLevel.MEMORY_AND_DISK))

    // add listener to check intermediate RDD non-default storage levels
    //添加侦听器来检查中间RDD非默认存储级别
    val nonDefaultListener = new IntermediateRDDStorageListener
    sc.addSparkListener(nonDefaultListener)
    val nonDefaultModel = als
      .setFinalStorageLevel("MEMORY_ONLY")
      .setIntermediateStorageLevel("DISK_ONLY")
      .fit(data)
    // check final factor RDD non-default storage levels
    //检查最终因素RDD非默认存储级别
    val levels = sc.getPersistentRDDs.collect {
      case (id, rdd) if rdd.name == "userFactors" && rdd.id != defaultFactorRDDs("userFactors")._1
        || rdd.name == "itemFactors" && rdd.id != defaultFactorRDDs("itemFactors")._1 =>
        rdd.getStorageLevel
    }
    levels.foreach(level => assert(level == StorageLevel.MEMORY_ONLY))
    nonDefaultListener.storageLevels.foreach(level => assert(level == StorageLevel.DISK_ONLY))
  }
}

private class IntermediateRDDStorageListener extends SparkListener {

  val storageLevels: mutable.ArrayBuffer[StorageLevel] = mutable.ArrayBuffer()

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
    val stageLevels = stageCompleted.stageInfo.rddInfos.collect {
      case info if info.name.contains("Blocks") || info.name.contains("Factors-") =>
        info.storageLevel
    }
    storageLevels ++= stageLevels
  }

}

object ALSSuite extends Logging {

  /**
   * Mapping from all Params to valid settings which differ from the defaults.
    * 从所有参数映射到与默认设置不同的有效设置
   * This is useful for tests which need to exercise all Params, such as save/load.
    * 这对于需要执行所有参数的测试非常有用,例如保存/加载
   * This excludes input columns to simplify some tests.
   */
  val allModelParamSettings: Map[String, Any] = Map(
    "predictionCol" -> "myPredictionCol"
  )

  /**
   * Mapping from all Params to valid settings which differ from the defaults.
   * This is useful for tests which need to exercise all Params, such as save/load.
   * This excludes input columns to simplify some tests.
   */
  val allEstimatorParamSettings: Map[String, Any] = allModelParamSettings ++ Map(
    "maxIter" -> 1,
    "rank" -> 1,
    "regParam" -> 0.01,
    "numUserBlocks" -> 2,
    "numItemBlocks" -> 2,
    "implicitPrefs" -> true,
    "alpha" -> 0.9,
    "nonnegative" -> true,
    "checkpointInterval" -> 20,
    "intermediateStorageLevel" -> "MEMORY_ONLY",
    "finalStorageLevel" -> "MEMORY_AND_DISK_SER"
  )

  // Helper functions to generate test data we share between ALS test suites

  /**
   * Generates random user/item factors, with i.i.d. values drawn from U(a, b).
    * 使用i.i.d生成随机用户/项目因子。 从U（a，b）中抽取的值。
   * @param size number of users/items
   * @param rank number of features
   * @param random random number generator
   * @param a min value of the support (default: -1)
   * @param b max value of the support (default: 1)
   * @return a sequence of (ID, factors) pairs
   */
  private def genFactors(
      size: Int,
      rank: Int,
      random: Random,
      a: Float = -1.0f,
      b: Float = 1.0f): Seq[(Int, Array[Float])] = {
    require(size > 0 && size < Int.MaxValue / 3)
    require(b > a)
    val ids = mutable.Set.empty[Int]
    while (ids.size < size) {
      ids += random.nextInt()
    }
    val width = b - a
    ids.toSeq.sorted.map(id => (id, Array.fill(rank)(a + random.nextFloat() * width)))
  }

  /**
   * Generates an implicit feedback dataset for testing ALS.
   *生成用于测试ALS的隐式反馈数据集。
   * @param sc SparkContext
   * @param numUsers number of users
   * @param numItems number of items
   * @param rank rank
   * @param noiseStd the standard deviation of additive Gaussian noise on training data
    *                 加性高斯噪声对训练数据的标准差
   * @param seed random seed
   * @return (training, test)
   */
  def genImplicitTestData(
      sc: SparkContext,
      numUsers: Int,
      numItems: Int,
      rank: Int,
      noiseStd: Double = 0.0,
      seed: Long = 11L): (RDD[Rating[Int]], RDD[Rating[Int]]) = {
    // The assumption of the implicit feedback model is that unobserved ratings are more likely to
    // be negatives.
    //隐式反馈模型的假设是未观测到的评级更可能是负面的
    val positiveFraction = 0.8
    val negativeFraction = 1.0 - positiveFraction
    val trainingFraction = 0.6
    val testFraction = 0.3
    val totalFraction = trainingFraction + testFraction
    val random = new Random(seed)
    val userFactors = genFactors(numUsers, rank, random)
    val itemFactors = genFactors(numItems, rank, random)
    val training = ArrayBuffer.empty[Rating[Int]]
    val test = ArrayBuffer.empty[Rating[Int]]
    for ((userId, userFactor) <- userFactors; (itemId, itemFactor) <- itemFactors) {
      val rating = blas.sdot(rank, userFactor, 1, itemFactor, 1)
      val threshold = if (rating > 0) positiveFraction else negativeFraction
      val observed = random.nextDouble() < threshold
      if (observed) {
        val x = random.nextDouble()
        if (x < totalFraction) {
          if (x < trainingFraction) {
            val noise = noiseStd * random.nextGaussian()
            training += Rating(userId, itemId, rating + noise.toFloat)
          } else {
            test += Rating(userId, itemId, rating)
          }
        }
      }
    }
    logInfo(s"Generated an implicit feedback dataset with ${training.size} ratings for training " +
      s"and ${test.size} for test.")
    (sc.parallelize(training, 2), sc.parallelize(test, 2))
  }
}
