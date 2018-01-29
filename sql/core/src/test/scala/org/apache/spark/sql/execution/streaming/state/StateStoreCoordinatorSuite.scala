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

package org.apache.spark.sql.execution.streaming.state

import java.util.UUID

import org.scalatest.concurrent.Eventually._
import org.scalatest.time.SpanSugar._

import org.apache.spark.{SharedSparkContext, SparkContext, SparkFunSuite}
import org.apache.spark.scheduler.ExecutorCacheTaskLocation
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.streaming.{MemoryStream, StreamingQueryWrapper}
import org.apache.spark.sql.functions.count
import org.apache.spark.util.Utils

class StateStoreCoordinatorSuite extends SparkFunSuite with SharedSparkContext {

  import StateStoreCoordinatorSuite._
  //报告，验证，getLocation
  test("report, verify, getLocation") {
    withCoordinatorRef(sc) { coordinatorRef =>
      val id = StateStoreProviderId(StateStoreId("x", 0, 0), UUID.randomUUID)

      assert(coordinatorRef.verifyIfInstanceActive(id, "exec1") === false)
      assert(coordinatorRef.getLocation(id) === None)

      coordinatorRef.reportActiveInstance(id, "hostX", "exec1")
      eventually(timeout(5 seconds)) {
        assert(coordinatorRef.verifyIfInstanceActive(id, "exec1") === true)
        assert(
          coordinatorRef.getLocation(id) ===
            Some(ExecutorCacheTaskLocation("hostX", "exec1").toString))
      }

      coordinatorRef.reportActiveInstance(id, "hostX", "exec2")

      eventually(timeout(5 seconds)) {
        assert(coordinatorRef.verifyIfInstanceActive(id, "exec1") === false)
        assert(coordinatorRef.verifyIfInstanceActive(id, "exec2") === true)

        assert(
          coordinatorRef.getLocation(id) ===
            Some(ExecutorCacheTaskLocation("hostX", "exec2").toString))
      }
    }
  }
  //使不活动
  test("make inactive") {
    withCoordinatorRef(sc) { coordinatorRef =>
      val runId1 = UUID.randomUUID
      val runId2 = UUID.randomUUID
      val id1 = StateStoreProviderId(StateStoreId("x", 0, 0), runId1)
      val id2 = StateStoreProviderId(StateStoreId("y", 1, 0), runId2)
      val id3 = StateStoreProviderId(StateStoreId("x", 0, 1), runId1)
      val host = "hostX"
      val exec = "exec1"

      coordinatorRef.reportActiveInstance(id1, host, exec)
      coordinatorRef.reportActiveInstance(id2, host, exec)
      coordinatorRef.reportActiveInstance(id3, host, exec)

      eventually(timeout(5 seconds)) {
        assert(coordinatorRef.verifyIfInstanceActive(id1, exec) === true)
        assert(coordinatorRef.verifyIfInstanceActive(id2, exec) === true)
        assert(coordinatorRef.verifyIfInstanceActive(id3, exec) === true)
      }

      coordinatorRef.deactivateInstances(runId1)

      assert(coordinatorRef.verifyIfInstanceActive(id1, exec) === false)
      assert(coordinatorRef.verifyIfInstanceActive(id2, exec) === true)
      assert(coordinatorRef.verifyIfInstanceActive(id3, exec) === false)

      assert(coordinatorRef.getLocation(id1) === None)
      assert(
        coordinatorRef.getLocation(id2) ===
          Some(ExecutorCacheTaskLocation(host, exec).toString))
      assert(coordinatorRef.getLocation(id3) === None)

      coordinatorRef.deactivateInstances(runId2)
      assert(coordinatorRef.verifyIfInstanceActive(id2, exec) === false)
      assert(coordinatorRef.getLocation(id2) === None)
    }
  }
  //多个引用有相同的基础协调员
  test("multiple references have same underlying coordinator") {
    withCoordinatorRef(sc) { coordRef1 =>
      val coordRef2 = StateStoreCoordinatorRef.forDriver(sc.env)

      val id = StateStoreProviderId(StateStoreId("x", 0, 0), UUID.randomUUID)

      coordRef1.reportActiveInstance(id, "hostX", "exec1")

      eventually(timeout(5 seconds)) {
        assert(coordRef2.verifyIfInstanceActive(id, "exec1") === true)
        assert(
          coordRef2.getLocation(id) ===
            Some(ExecutorCacheTaskLocation("hostX", "exec1").toString))
      }
    }
  }
  //查询停止关闭相关的商店提供商
  test("query stop deactivates related store providers") {
    var coordRef: StateStoreCoordinatorRef = null
    try {
      val spark = SparkSession.builder().sparkContext(sc).getOrCreate()
      SparkSession.setActiveSession(spark)
      import spark.implicits._
      coordRef = spark.streams.stateStoreCoordinator
      implicit val sqlContext = spark.sqlContext
      spark.conf.set("spark.sql.shuffle.partitions", "1")

      // Start a query and run a batch to load state stores
      val inputData = MemoryStream[Int]
      val aggregated = inputData.toDF().groupBy("value").agg(count("*")) // stateful query
      val checkpointLocation = Utils.createTempDir().getAbsoluteFile
      val query = aggregated.writeStream
        .format("memory")
        .outputMode("update")
        .queryName("query")
        .option("checkpointLocation", checkpointLocation.toString)
        .start()
      inputData.addData(1, 2, 3)
      query.processAllAvailable()

      // Verify state store has been loaded
      //验证状态存储已被加载
      val stateCheckpointDir =
        query.asInstanceOf[StreamingQueryWrapper].streamingQuery.lastExecution.checkpointLocation
      val providerId = StateStoreProviderId(StateStoreId(stateCheckpointDir, 0, 0), query.runId)
      assert(coordRef.getLocation(providerId).nonEmpty)

      // Stop and verify whether the stores are deactivated in the coordinator
      //停止并验证商店是否在协调器中停用
      query.stop()
      assert(coordRef.getLocation(providerId).isEmpty)
    } finally {
      SparkSession.getActiveSession.foreach(_.streams.active.foreach(_.stop()))
      if (coordRef != null) coordRef.stop()
      StateStore.stop()
    }
  }
}

object StateStoreCoordinatorSuite {
  def withCoordinatorRef(sc: SparkContext)(body: StateStoreCoordinatorRef => Unit): Unit = {
    var coordinatorRef: StateStoreCoordinatorRef = null
    try {
      coordinatorRef = StateStoreCoordinatorRef.forDriver(sc.env)
      body(coordinatorRef)
    } finally {
      if (coordinatorRef != null) coordinatorRef.stop()
    }
  }
}