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

// scalastyle:off println
package org.apache.spark.examples.ml

// $example on$
import org.apache.spark.ml.feature.RFormula
// $example off$

object RFormulaExample extends SparkCommant{
  def main(args: Array[String]): Unit = {
    // $example on$
    val dataset = spark.createDataFrame(Seq(
      (7, "US", 18, 1.0),
      (8, "CA", 12, 0.0),
      (9, "NZ", 15, 0.0)
    )).toDF("id", "country", "hour", "clicked")
    // RFormula通过R模型公式来选择列
    //RFormula产生一个向量特征列以及一个double或者字符串标签列

    val formula = new RFormula()
      .setFormula("clicked ~ country + hour")
      .setFeaturesCol("features")
      .setLabelCol("label")

    val output = formula.fit(dataset).transform(dataset)

    /**
      * +--------------+-----+
        |      features|label|
        +--------------+-----+
        |[0.0,0.0,18.0]|  1.0|
        |[1.0,0.0,12.0]|  0.0|
        |[0.0,1.0,15.0]|  0.0|
        +--------------+-----+
      */
    output.select("features", "label").show()
    // $example off$

    spark.stop()
  }
}
// scalastyle:on println
