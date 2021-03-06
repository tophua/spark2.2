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

package org.apache.spark.ml.evaluation

import org.apache.spark.SparkFunSuite
import org.apache.spark.ml.param.ParamsSuite
import org.apache.spark.ml.util.DefaultReadWriteTest
import org.apache.spark.ml.util.TestingUtils._
import org.apache.spark.mllib.util.MLlibTestSparkContext
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.types.IntegerType


class ClusteringEvaluatorSuite
  extends SparkFunSuite with MLlibTestSparkContext with DefaultReadWriteTest {

  import testImplicits._

  test("params") {
    ParamsSuite.checkParams(new ClusteringEvaluator)
  }

  test("read/write") {
    val evaluator = new ClusteringEvaluator()
      .setPredictionCol("myPrediction")
      .setFeaturesCol("myLabel")
    testDefaultReadWrite(evaluator)
  }

  /*
    Use the following python code to load the data and evaluate it using scikit-learn package.
    使用下面的python代码来加载数据并使用scikit-learn软件包进行评估
    from sklearn import datasets
    from sklearn.metrics import silhouette_score
    iris = datasets.load_iris()
    round(silhouette_score(iris.data, iris.target, metric='sqeuclidean'), 10)

    0.6564679231
  */
  //
  test("squared euclidean Silhouette") {
    val iris = ClusteringEvaluatorSuite.irisDataset(spark)
    val evaluator = new ClusteringEvaluator()
        .setFeaturesCol("features")
        .setPredictionCol("label")

    assert(evaluator.evaluate(iris) ~== 0.6564679231 relTol 1e-5)
  }
  //群集数量必须大于一个
  test("number of clusters must be greater than one") {
    val iris = ClusteringEvaluatorSuite.irisDataset(spark)
      .where($"label" === 0.0)
    val evaluator = new ClusteringEvaluator()
      .setFeaturesCol("features")
      .setPredictionCol("label")

    val e = intercept[AssertionError]{
      evaluator.evaluate(iris)
    }
    assert(e.getMessage.contains("Number of clusters must be greater than one"))
  }

}

object ClusteringEvaluatorSuite {
  def irisDataset(spark: SparkSession): DataFrame = {

    val irisPath = Thread.currentThread()
      .getContextClassLoader
      .getResource("test-data/iris.libsvm")
      .toString

    spark.read.format("libsvm").load(irisPath)
  }
}
