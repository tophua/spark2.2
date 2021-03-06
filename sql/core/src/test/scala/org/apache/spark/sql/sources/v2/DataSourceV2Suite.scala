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

package org.apache.spark.sql.sources.v2

import java.util.{ArrayList, List => JList}

import test.org.apache.spark.sql.sources.v2._

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.sources.{Filter, GreaterThan}
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.StructType

class DataSourceV2Suite extends QueryTest with SharedSQLContext {
  import testImplicits._
  //最简单的实现
  test("simplest implementation") {
    Seq(classOf[SimpleDataSourceV2], classOf[JavaSimpleDataSourceV2]).foreach { cls =>
      withClue(cls.getName) {
        val df = spark.read.format(cls.getName).load()
        checkAnswer(df, (0 until 10).map(i => Row(i, -i)))
        checkAnswer(df.select('j), (0 until 10).map(i => Row(-i)))
        checkAnswer(df.filter('i > 5), (6 until 10).map(i => Row(i, -i)))
      }
    }
  }
  //高级的实施
  test("advanced implementation") {
    Seq(classOf[AdvancedDataSourceV2], classOf[JavaAdvancedDataSourceV2]).foreach { cls =>
      withClue(cls.getName) {
        val df = spark.read.format(cls.getName).load()
        checkAnswer(df, (0 until 10).map(i => Row(i, -i)))
        checkAnswer(df.select('j), (0 until 10).map(i => Row(-i)))
        checkAnswer(df.filter('i > 3), (4 until 10).map(i => Row(i, -i)))
        checkAnswer(df.select('j).filter('i > 6), (7 until 10).map(i => Row(-i)))
        checkAnswer(df.select('i).filter('i > 10), Nil)
      }
    }
  }
  //不安全的行执行
  test("unsafe row implementation") {
    Seq(classOf[UnsafeRowDataSourceV2], classOf[JavaUnsafeRowDataSourceV2]).foreach { cls =>
      withClue(cls.getName) {
        val df = spark.read.format(cls.getName).load()
        checkAnswer(df, (0 until 10).map(i => Row(i, -i)))
        checkAnswer(df.select('j), (0 until 10).map(i => Row(-i)))
        checkAnswer(df.filter('i > 5), (6 until 10).map(i => Row(i, -i)))
      }
    }
  }
  //架构所需的数据源
  test("schema required data source") {
    Seq(classOf[SchemaRequiredDataSource], classOf[JavaSchemaRequiredDataSource]).foreach { cls =>
      withClue(cls.getName) {
        val e = intercept[AnalysisException](spark.read.format(cls.getName).load())
        assert(e.message.contains("A schema needs to be specified"))

        val schema = new StructType().add("i", "int").add("s", "string")
        val df = spark.read.format(cls.getName).schema(schema).load()

        assert(df.schema == schema)
        assert(df.collect().isEmpty)
      }
    }
  }
}

class SimpleDataSourceV2 extends DataSourceV2 with ReadSupport {

  class Reader extends DataSourceV2Reader {
    override def readSchema(): StructType = new StructType().add("i", "int").add("j", "int")

    override def createReadTasks(): JList[ReadTask[Row]] = {
      java.util.Arrays.asList(new SimpleReadTask(0, 5), new SimpleReadTask(5, 10))
    }
  }

  override def createReader(options: DataSourceV2Options): DataSourceV2Reader = new Reader
}

class SimpleReadTask(start: Int, end: Int) extends ReadTask[Row] with DataReader[Row] {
  private var current = start - 1

  override def createReader(): DataReader[Row] = new SimpleReadTask(start, end)

  override def next(): Boolean = {
    current += 1
    current < end
  }

  override def get(): Row = Row(current, -current)

  override def close(): Unit = {}
}



class AdvancedDataSourceV2 extends DataSourceV2 with ReadSupport {

  class Reader extends DataSourceV2Reader
    with SupportsPushDownRequiredColumns with SupportsPushDownFilters {

    var requiredSchema = new StructType().add("i", "int").add("j", "int")
    var filters = Array.empty[Filter]

    override def pruneColumns(requiredSchema: StructType): Unit = {
      this.requiredSchema = requiredSchema
    }

    override def pushFilters(filters: Array[Filter]): Array[Filter] = {
      this.filters = filters
      Array.empty
    }

    override def readSchema(): StructType = {
      requiredSchema
    }

    override def createReadTasks(): JList[ReadTask[Row]] = {
      val lowerBound = filters.collect {
        case GreaterThan("i", v: Int) => v
      }.headOption

      val res = new ArrayList[ReadTask[Row]]

      if (lowerBound.isEmpty) {
        res.add(new AdvancedReadTask(0, 5, requiredSchema))
        res.add(new AdvancedReadTask(5, 10, requiredSchema))
      } else if (lowerBound.get < 4) {
        res.add(new AdvancedReadTask(lowerBound.get + 1, 5, requiredSchema))
        res.add(new AdvancedReadTask(5, 10, requiredSchema))
      } else if (lowerBound.get < 9) {
        res.add(new AdvancedReadTask(lowerBound.get + 1, 10, requiredSchema))
      }

      res
    }
  }

  override def createReader(options: DataSourceV2Options): DataSourceV2Reader = new Reader
}

class AdvancedReadTask(start: Int, end: Int, requiredSchema: StructType)
  extends ReadTask[Row] with DataReader[Row] {

  private var current = start - 1

  override def createReader(): DataReader[Row] = new AdvancedReadTask(start, end, requiredSchema)

  override def close(): Unit = {}

  override def next(): Boolean = {
    current += 1
    current < end
  }

  override def get(): Row = {
    val values = requiredSchema.map(_.name).map {
      case "i" => current
      case "j" => -current
    }
    Row.fromSeq(values)
  }
}


class UnsafeRowDataSourceV2 extends DataSourceV2 with ReadSupport {

  class Reader extends DataSourceV2Reader with SupportsScanUnsafeRow {
    override def readSchema(): StructType = new StructType().add("i", "int").add("j", "int")

    override def createUnsafeRowReadTasks(): JList[ReadTask[UnsafeRow]] = {
      java.util.Arrays.asList(new UnsafeRowReadTask(0, 5), new UnsafeRowReadTask(5, 10))
    }
  }

  override def createReader(options: DataSourceV2Options): DataSourceV2Reader = new Reader
}

class UnsafeRowReadTask(start: Int, end: Int)
  extends ReadTask[UnsafeRow] with DataReader[UnsafeRow] {

  private val row = new UnsafeRow(2)
  row.pointTo(new Array[Byte](8 * 3), 8 * 3)

  private var current = start - 1

  override def createReader(): DataReader[UnsafeRow] = new UnsafeRowReadTask(start, end)

  override def next(): Boolean = {
    current += 1
    current < end
  }
  override def get(): UnsafeRow = {
    row.setInt(0, current)
    row.setInt(1, -current)
    row
  }

  override def close(): Unit = {}
}

class SchemaRequiredDataSource extends DataSourceV2 with ReadSupportWithSchema {

  class Reader(val readSchema: StructType) extends DataSourceV2Reader {
    override def createReadTasks(): JList[ReadTask[Row]] =
      java.util.Collections.emptyList()
  }

  override def createReader(schema: StructType, options: DataSourceV2Options): DataSourceV2Reader =
    new Reader(schema)
}
