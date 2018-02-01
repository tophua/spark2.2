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

package org.apache.spark.examples.ml

// scalastyle:off println

// $example on$
import org.apache.spark.ml.clustering.KMeans
// $example off$
import org.apache.spark.sql.SparkSession

/**
 * An example demonstrating k-means clustering.
 * Run with
 * {{{
 * bin/run-example ml.KMeansExample
 * }}}
 */
object KMeansExample {

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName(s"${this.getClass.getSimpleName}")
      .getOrCreate()

    // $example on$
    // Loads data.
    //加载和解析数据文件
    val dataset = spark.read.format("libsvm").load("data/mllib/sample_kmeans_data.txt")

    // Trains a k-means model.
    //训练一个k-means模型
    val kmeans = new KMeans()
      .setK(2)//聚类簇数
      .setSeed(1L)
    val model = kmeans.fit(dataset)

    // Evaluate clustering by computing Within Set Sum of Squared Errors.
   //通过计算平方误差平方和评估聚类
    val WSSSE = model.computeCost(dataset)
    println(s"Within Set Sum of Squared Errors = $WSSSE")

    // Shows the result. 显示结果
    println("Cluster Centers: ")
    //聚类中心点
    model.clusterCenters.foreach(println)
    // $example off$

    spark.stop()
  }
}
// scalastyle:on println
