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
package org.apache.spark.sql.execution.arrow

import java.io.File
import java.nio.charset.StandardCharsets
import java.sql.{Date, Timestamp}
import java.text.SimpleDateFormat
import java.util.Locale

import com.google.common.io.Files
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{VectorLoader, VectorSchemaRoot}
import org.apache.arrow.vector.file.json.JsonFileReader
import org.apache.arrow.vector.util.Validator
import org.scalatest.BeforeAndAfterAll

import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.{BinaryType, IntegerType, StructField, StructType}
import org.apache.spark.util.Utils


class ArrowConvertersSuite extends SharedSQLContext with BeforeAndAfterAll {
  import testImplicits._

  private var tempDataPath: String = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    tempDataPath = Utils.createTempDir(namePrefix = "arrow").getAbsolutePath
  }
  //收集到箭头记录批次
  test("collect to arrow record batch") {
    val indexData = (1 to 6).toDF("i")
    val arrowPayloads = indexData.toArrowPayload.collect()
    assert(arrowPayloads.nonEmpty)
    assert(arrowPayloads.length == indexData.rdd.getNumPartitions)
    val allocator = new RootAllocator(Long.MaxValue)
    val arrowRecordBatches = arrowPayloads.map(_.loadBatch(allocator))
    val rowCount = arrowRecordBatches.map(_.getLength).sum
    assert(rowCount === indexData.count())
    arrowRecordBatches.foreach(batch => assert(batch.getNodes.size() > 0))
    arrowRecordBatches.foreach(_.close())
    allocator.close()
  }
  //short的转换
  test("short conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_s",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 16
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 16
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_s",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 16
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 16
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_s",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 2, -2, 32767, -32768 ]
         |    }, {
         |      "name" : "b_s",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1, 0, 0, -2, 0, -32768 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_s = List[Short](1, -1, 2, -2, 32767, -32768)
    val b_s = List[Option[Short]](Some(1), None, None, Some(-2), None, Some(-32768))
    val df = a_s.zip(b_s).toDF("a_s", "b_s")

    collectAndValidate(df, json, "integer-16bit.json")
  }
  //int的转换
  test("int conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 2, -2, 2147483647, -2147483648 ]
         |    }, {
         |      "name" : "b_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1, 0, 0, -2, 0, -2147483648 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_i = List[Int](1, -1, 2, -2, 2147483647, -2147483648)
    val b_i = List[Option[Int]](Some(1), None, None, Some(-2), None, Some(-2147483648))
    val df = a_i.zip(b_i).toDF("a_i", "b_i")

    collectAndValidate(df, json, "integer-32bit.json")
  }
  //long的转换
  test("long conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_l",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 64
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_l",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 64
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_l",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 2, -2, 9223372036854775807, -9223372036854775808 ]
         |    }, {
         |      "name" : "b_l",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1, 0, 0, -2, 0, -9223372036854775808 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_l = List[Long](1, -1, 2, -2, 9223372036854775807L, -9223372036854775808L)
    val b_l = List[Option[Long]](Some(1), None, None, Some(-2), None, Some(-9223372036854775808L))
    val df = a_l.zip(b_l).toDF("a_l", "b_l")

    collectAndValidate(df, json, "integer-64bit.json")
  }
  //float的转换
  test("float conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_f",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "SINGLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_f",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "SINGLE"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_f",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1.0, 2.0, 0.01, 200.0, 0.0001, 20000.0 ]
         |    }, {
         |      "name" : "b_f",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1.1, 0.0, 0.0, 2.2, 0.0, 3.3 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_f = List(1.0f, 2.0f, 0.01f, 200.0f, 0.0001f, 20000.0f)
    val b_f = List[Option[Float]](Some(1.1f), None, None, Some(2.2f), None, Some(3.3f))
    val df = a_f.zip(b_f).toDF("a_f", "b_f")

    collectAndValidate(df, json, "floating_point-single_precision.json")
  }
  //double的转换
  test("double conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_d",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "DOUBLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_d",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "DOUBLE"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_d",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1.0, 2.0, 0.01, 200.0, 1.0E-4, 20000.0 ]
         |    }, {
         |      "name" : "b_d",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1.1, 0.0, 0.0, 2.2, 0.0, 3.3 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_d = List(1.0, 2.0, 0.01, 200.0, 0.0001, 20000.0)
    val b_d = List[Option[Double]](Some(1.1), None, None, Some(2.2), None, Some(3.3))
    val df = a_d.zip(b_d).toDF("a_d", "b_d")

    collectAndValidate(df, json, "floating_point-double_precision.json")
  }
  //index的转换
  test("index conversion") {
    val data = List[Int](1, 2, 3, 4, 5, 6)
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, 2, 3, 4, 5, 6 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin
    val df = data.toDF("i")

    collectAndValidate(df, json, "indexData-ints.json")
  }
  //混合数字类型转换
  test("mixed numeric type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 16
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 16
         |        } ]
         |      }
         |    }, {
         |      "name" : "b",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "SINGLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "c",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "d",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "DOUBLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    }, {
         |      "name" : "e",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 64
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, 2, 3, 4, 5, 6 ]
         |    }, {
         |      "name" : "b",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1.0, 2.0, 3.0, 4.0, 5.0, 6.0 ]
         |    }, {
         |      "name" : "c",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, 2, 3, 4, 5, 6 ]
         |    }, {
         |      "name" : "d",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1.0, 2.0, 3.0, 4.0, 5.0, 6.0 ]
         |    }, {
         |      "name" : "e",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, 2, 3, 4, 5, 6 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val data = List(1, 2, 3, 4, 5, 6)
    val data_tuples = for (d <- data) yield {
      (d.toShort, d.toFloat, d.toInt, d.toDouble, d.toLong)
    }
    val df = data_tuples.toDF("a", "b", "c", "d", "e")

    collectAndValidate(df, json, "mixed_numeric_types.json")
  }
  //string类型转换
  test("string type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "upper_case",
         |      "type" : {
         |        "name" : "utf8"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 8
         |        } ]
         |      }
         |    }, {
         |      "name" : "lower_case",
         |      "type" : {
         |        "name" : "utf8"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 8
         |        } ]
         |      }
         |    }, {
         |      "name" : "null_str",
         |      "type" : {
         |        "name" : "utf8"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 8
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 3,
         |    "columns" : [ {
         |      "name" : "upper_case",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "OFFSET" : [ 0, 1, 2, 3 ],
         |      "DATA" : [ "A", "B", "C" ]
         |    }, {
         |      "name" : "lower_case",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "OFFSET" : [ 0, 1, 2, 3 ],
         |      "DATA" : [ "a", "b", "c" ]
         |    }, {
         |      "name" : "null_str",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 0 ],
         |      "OFFSET" : [ 0, 2, 5, 5 ],
         |      "DATA" : [ "ab", "CDE", "" ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val upperCase = Seq("A", "B", "C")
    val lowerCase = Seq("a", "b", "c")
    val nullStr = Seq("ab", "CDE", null)
    val df = (upperCase, lowerCase, nullStr).zipped.toList
      .toDF("upper_case", "lower_case", "null_str")

    collectAndValidate(df, json, "stringData.json")
  }
  //boolean类型转换
  test("boolean type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_bool",
         |      "type" : {
         |        "name" : "bool"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 1
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 4,
         |    "columns" : [ {
         |      "name" : "a_bool",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 1, 1, 1 ],
         |      "DATA" : [ true, true, false, true ]
         |    } ]
         |  } ]
         |}
       """.stripMargin
    val df = Seq(true, true, false, true).toDF("a_bool")
    collectAndValidate(df, json, "boolData.json")
  }
  //byte类型转换
  test("byte type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_byte",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 8
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 8
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 4,
         |    "columns" : [ {
         |      "name" : "a_byte",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 64, 127 ]
         |    } ]
         |  } ]
         |}
         |
       """.stripMargin
    val df = List[Byte](1.toByte, (-1).toByte, 64.toByte, Byte.MaxValue).toDF("a_byte")
    collectAndValidate(df, json, "byteData.json")
  }
  //binary类型转换
  test("binary type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_binary",
         |      "type" : {
         |        "name" : "binary"
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 8
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 3,
         |    "columns" : [ {
         |      "name" : "a_binary",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "OFFSET" : [ 0, 3, 4, 6 ],
         |      "DATA" : [ "616263", "64", "6566" ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val data = Seq("abc", "d", "ef")
    val rdd = sparkContext.parallelize(data.map(s => Row(s.getBytes("utf-8"))))
    val df = spark.createDataFrame(rdd, StructType(Seq(StructField("a_binary", BinaryType))))

    collectAndValidate(df, json, "binaryData.json")
  }
  //binary类型转换
  test("floating-point NaN") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "NaN_f",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "SINGLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "NaN_d",
         |      "type" : {
         |        "name" : "floatingpoint",
         |        "precision" : "DOUBLE"
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 64
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 2,
         |    "columns" : [ {
         |      "name" : "NaN_f",
         |      "count" : 2,
         |      "VALIDITY" : [ 1, 1 ],
         |      "DATA" : [ 1.2000000476837158, "NaN" ]
         |    }, {
         |      "name" : "NaN_d",
         |      "count" : 2,
         |      "VALIDITY" : [ 1, 1 ],
         |      "DATA" : [ "NaN", 1.2 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val fnan = Seq(1.2F, Float.NaN)
    val dnan = Seq(Double.NaN, 1.2)
    val df = fnan.zip(dnan).toDF("NaN_f", "NaN_d")

    collectAndValidate(df, json, "nanData-floating_point.json")
  }
  //array类型转换
  test("array type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_arr",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "list"
         |      },
         |      "children" : [ {
         |        "name" : "element",
         |        "nullable" : false,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_arr",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "list"
         |      },
         |      "children" : [ {
         |        "name" : "element",
         |        "nullable" : false,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "c_arr",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "list"
         |      },
         |      "children" : [ {
         |        "name" : "element",
         |        "nullable" : true,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "d_arr",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "list"
         |      },
         |      "children" : [ {
         |        "name" : "element",
         |        "nullable" : true,
         |        "type" : {
         |          "name" : "list"
         |        },
         |        "children" : [ {
         |          "name" : "element",
         |          "nullable" : false,
         |          "type" : {
         |            "name" : "int",
         |            "bitWidth" : 32,
         |            "isSigned" : true
         |          },
         |          "children" : [ ],
         |          "typeLayout" : {
         |            "vectors" : [ {
         |              "type" : "VALIDITY",
         |              "typeBitWidth" : 1
         |            }, {
         |              "type" : "DATA",
         |              "typeBitWidth" : 32
         |            } ]
         |          }
         |        } ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "OFFSET",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "OFFSET",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 4,
         |    "columns" : [ {
         |      "name" : "a_arr",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 1, 1, 1 ],
         |      "OFFSET" : [ 0, 2, 4, 4, 5 ],
         |      "children" : [ {
         |        "name" : "element",
         |        "count" : 5,
         |        "VALIDITY" : [ 1, 1, 1, 1, 1 ],
         |        "DATA" : [ 1, 2, 3, 4, 5 ]
         |      } ]
         |    }, {
         |      "name" : "b_arr",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 0, 1, 0 ],
         |      "OFFSET" : [ 0, 2, 2, 2, 2 ],
         |      "children" : [ {
         |        "name" : "element",
         |        "count" : 2,
         |        "VALIDITY" : [ 1, 1 ],
         |        "DATA" : [ 1, 2 ]
         |      } ]
         |    }, {
         |      "name" : "c_arr",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 1, 1, 1 ],
         |      "OFFSET" : [ 0, 2, 4, 4, 5 ],
         |      "children" : [ {
         |        "name" : "element",
         |        "count" : 5,
         |        "VALIDITY" : [ 1, 1, 1, 0, 1 ],
         |        "DATA" : [ 1, 2, 3, 0, 5 ]
         |      } ]
         |    }, {
         |      "name" : "d_arr",
         |      "count" : 4,
         |      "VALIDITY" : [ 1, 1, 1, 1 ],
         |      "OFFSET" : [ 0, 1, 3, 3, 4 ],
         |      "children" : [ {
         |        "name" : "element",
         |        "count" : 4,
         |        "VALIDITY" : [ 1, 1, 1, 1 ],
         |        "OFFSET" : [ 0, 2, 3, 3, 4 ],
         |        "children" : [ {
         |          "name" : "element",
         |          "count" : 4,
         |          "VALIDITY" : [ 1, 1, 1, 1 ],
         |          "DATA" : [ 1, 2, 3, 5 ]
         |        } ]
         |      } ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val aArr = Seq(Seq(1, 2), Seq(3, 4), Seq(), Seq(5))
    val bArr = Seq(Some(Seq(1, 2)), None, Some(Seq()), None)
    val cArr = Seq(Seq(Some(1), Some(2)), Seq(Some(3), None), Seq(), Seq(Some(5)))
    val dArr = Seq(Seq(Seq(1, 2)), Seq(Seq(3), Seq()), Seq(), Seq(Seq(5)))

    val df = aArr.zip(bArr).zip(cArr).zip(dArr).map {
      case (((a, b), c), d) => (a, b, c, d)
    }.toDF("a_arr", "b_arr", "c_arr", "d_arr")

    collectAndValidate(df, json, "arrayData.json")
  }
  //struct类型转换
  test("struct type conversion") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_struct",
         |      "nullable" : false,
         |      "type" : {
         |        "name" : "struct"
         |      },
         |      "children" : [ {
         |        "name" : "i",
         |        "nullable" : false,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_struct",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "struct"
         |      },
         |      "children" : [ {
         |        "name" : "i",
         |        "nullable" : false,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        } ]
         |      }
         |    }, {
         |      "name" : "c_struct",
         |      "nullable" : false,
         |      "type" : {
         |        "name" : "struct"
         |      },
         |      "children" : [ {
         |        "name" : "i",
         |        "nullable" : true,
         |        "type" : {
         |          "name" : "int",
         |          "bitWidth" : 32,
         |          "isSigned" : true
         |        },
         |        "children" : [ ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          }, {
         |            "type" : "DATA",
         |            "typeBitWidth" : 32
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        } ]
         |      }
         |    }, {
         |      "name" : "d_struct",
         |      "nullable" : true,
         |      "type" : {
         |        "name" : "struct"
         |      },
         |      "children" : [ {
         |        "name" : "nested",
         |        "nullable" : true,
         |        "type" : {
         |          "name" : "struct"
         |        },
         |        "children" : [ {
         |          "name" : "i",
         |          "nullable" : true,
         |          "type" : {
         |            "name" : "int",
         |            "bitWidth" : 32,
         |            "isSigned" : true
         |          },
         |          "children" : [ ],
         |          "typeLayout" : {
         |            "vectors" : [ {
         |              "type" : "VALIDITY",
         |              "typeBitWidth" : 1
         |            }, {
         |              "type" : "DATA",
         |              "typeBitWidth" : 32
         |            } ]
         |          }
         |        } ],
         |        "typeLayout" : {
         |          "vectors" : [ {
         |            "type" : "VALIDITY",
         |            "typeBitWidth" : 1
         |          } ]
         |        }
         |      } ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 3,
         |    "columns" : [ {
         |      "name" : "a_struct",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "children" : [ {
         |        "name" : "i",
         |        "count" : 3,
         |        "VALIDITY" : [ 1, 1, 1 ],
         |        "DATA" : [ 1, 2, 3 ]
         |      } ]
         |    }, {
         |      "name" : "b_struct",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 0, 1 ],
         |      "children" : [ {
         |        "name" : "i",
         |        "count" : 3,
         |        "VALIDITY" : [ 1, 0, 1 ],
         |        "DATA" : [ 1, 2, 3 ]
         |      } ]
         |    }, {
         |      "name" : "c_struct",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "children" : [ {
         |        "name" : "i",
         |        "count" : 3,
         |        "VALIDITY" : [ 1, 0, 1 ],
         |        "DATA" : [ 1, 2, 3 ]
         |      } ]
         |    }, {
         |      "name" : "d_struct",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 0, 1 ],
         |      "children" : [ {
         |        "name" : "nested",
         |        "count" : 3,
         |        "VALIDITY" : [ 1, 0, 0 ],
         |        "children" : [ {
         |          "name" : "i",
         |          "count" : 3,
         |          "VALIDITY" : [ 1, 0, 0 ],
         |          "DATA" : [ 1, 2, 0 ]
         |        } ]
         |      } ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val aStruct = Seq(Row(1), Row(2), Row(3))
    val bStruct = Seq(Row(1), null, Row(3))
    val cStruct = Seq(Row(1), Row(null), Row(3))
    val dStruct = Seq(Row(Row(1)), null, Row(null))
    val data = aStruct.zip(bStruct).zip(cStruct).zip(dStruct).map {
      case (((a, b), c), d) => Row(a, b, c, d)
    }

    val rdd = sparkContext.parallelize(data)
    val schema = new StructType()
      .add("a_struct", new StructType().add("i", IntegerType, nullable = false), nullable = false)
      .add("b_struct", new StructType().add("i", IntegerType, nullable = false), nullable = true)
      .add("c_struct", new StructType().add("i", IntegerType, nullable = true), nullable = false)
      .add("d_struct", new StructType().add("nested", new StructType().add("i", IntegerType)))
    val df = spark.createDataFrame(rdd, schema)

    collectAndValidate(df, json, "structData.json")
  }

  test("partitioned DataFrame") {
    val json1 =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 3,
         |    "columns" : [ {
         |      "name" : "a",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "DATA" : [ 1, 1, 2 ]
         |    }, {
         |      "name" : "b",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "DATA" : [ 1, 2, 1 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin
    val json2 =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 3,
         |    "columns" : [ {
         |      "name" : "a",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "DATA" : [ 2, 3, 3 ]
         |    }, {
         |      "name" : "b",
         |      "count" : 3,
         |      "VALIDITY" : [ 1, 1, 1 ],
         |      "DATA" : [ 2, 1, 2 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val arrowPayloads = testData2.toArrowPayload.collect()
    // NOTE: testData2 should have 2 partitions -> 2 arrow batches in payload
    assert(arrowPayloads.length === 2)
    val schema = testData2.schema

    val tempFile1 = new File(tempDataPath, "testData2-ints-part1.json")
    val tempFile2 = new File(tempDataPath, "testData2-ints-part2.json")
    Files.write(json1, tempFile1, StandardCharsets.UTF_8)
    Files.write(json2, tempFile2, StandardCharsets.UTF_8)

    validateConversion(schema, arrowPayloads(0), tempFile1)
    validateConversion(schema, arrowPayloads(1), tempFile2)
  }
  //空框架收集
  test("empty frame collect") {
    val arrowPayload = spark.emptyDataFrame.toArrowPayload.collect()
    assert(arrowPayload.isEmpty)

    val filteredDF = List[Int](1, 2, 3, 4, 5, 6).toDF("i")
    val filteredArrowPayload = filteredDF.filter("i < 0").toArrowPayload.collect()
    assert(filteredArrowPayload.isEmpty)
  }
  //空分区收集
  test("empty partition collect") {
    val emptyPart = spark.sparkContext.parallelize(Seq(1), 2).toDF("i")
    val arrowPayloads = emptyPart.toArrowPayload.collect()
    assert(arrowPayloads.length === 1)
    val allocator = new RootAllocator(Long.MaxValue)
    val arrowRecordBatches = arrowPayloads.map(_.loadBatch(allocator))
    assert(arrowRecordBatches.head.getLength == 1)
    arrowRecordBatches.foreach(_.close())
    allocator.close()
  }
  //批量配置中的最大记录数
  test("max records in batch conf") {
    val totalRecords = 10
    val maxRecordsPerBatch = 3
    spark.conf.set("spark.sql.execution.arrow.maxRecordsPerBatch", maxRecordsPerBatch)
    val df = spark.sparkContext.parallelize(1 to totalRecords, 2).toDF("i")
    val arrowPayloads = df.toArrowPayload.collect()
    assert(arrowPayloads.length >= 4)
    val allocator = new RootAllocator(Long.MaxValue)
    val arrowRecordBatches = arrowPayloads.map(_.loadBatch(allocator))
    var recordCount = 0
    arrowRecordBatches.foreach { batch =>
      assert(batch.getLength > 0)
      assert(batch.getLength <= maxRecordsPerBatch)
      recordCount += batch.getLength
      batch.close()
    }
    assert(recordCount == totalRecords)
    allocator.close()
    spark.conf.unset("spark.sql.execution.arrow.maxRecordsPerBatch")
  }
  //不受支持的类型
  testQuietly("unsupported types") {
    def runUnsupported(block: => Unit): Unit = {
      val msg = intercept[SparkException] {
        block
      }
      assert(msg.getMessage.contains("Unsupported data type"))
      assert(msg.getCause.getClass === classOf[UnsupportedOperationException])
    }

    runUnsupported { decimalData.toArrowPayload.collect() }
    runUnsupported { mapData.toDF().toArrowPayload.collect() }
    runUnsupported { complexData.toArrowPayload.collect() }

    val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
    val d1 = new Date(sdf.parse("2015-04-08 13:10:15.000 UTC").getTime)
    val d2 = new Date(sdf.parse("2016-05-09 13:10:15.000 UTC").getTime)
    runUnsupported { Seq(d1, d2).toDF("date").toArrowPayload.collect() }

    val ts1 = new Timestamp(sdf.parse("2013-04-08 01:10:15.567 UTC").getTime)
    val ts2 = new Timestamp(sdf.parse("2013-04-08 13:10:10.789 UTC").getTime)
    runUnsupported { Seq(ts1, ts2).toDF("timestamp").toArrowPayload.collect() }
  }
  //测试箭头验证器
  test("test Arrow Validator") {
    val json =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "a_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "b_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 2, -2, 2147483647, -2147483648 ]
         |    }, {
         |      "name" : "b_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1, 0, 0, -2, 0, -2147483648 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin
    val json_diff_col_order =
      s"""
         |{
         |  "schema" : {
         |    "fields" : [ {
         |      "name" : "b_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : true,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    }, {
         |      "name" : "a_i",
         |      "type" : {
         |        "name" : "int",
         |        "isSigned" : true,
         |        "bitWidth" : 32
         |      },
         |      "nullable" : false,
         |      "children" : [ ],
         |      "typeLayout" : {
         |        "vectors" : [ {
         |          "type" : "VALIDITY",
         |          "typeBitWidth" : 1
         |        }, {
         |          "type" : "DATA",
         |          "typeBitWidth" : 32
         |        } ]
         |      }
         |    } ]
         |  },
         |  "batches" : [ {
         |    "count" : 6,
         |    "columns" : [ {
         |      "name" : "a_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 1, 1, 1, 1, 1 ],
         |      "DATA" : [ 1, -1, 2, -2, 2147483647, -2147483648 ]
         |    }, {
         |      "name" : "b_i",
         |      "count" : 6,
         |      "VALIDITY" : [ 1, 0, 0, 1, 0, 1 ],
         |      "DATA" : [ 1, 0, 0, -2, 0, -2147483648 ]
         |    } ]
         |  } ]
         |}
       """.stripMargin

    val a_i = List[Int](1, -1, 2, -2, 2147483647, -2147483648)
    val b_i = List[Option[Int]](Some(1), None, None, Some(-2), None, Some(-2147483648))
    val df = a_i.zip(b_i).toDF("a_i", "b_i")

    // Different schema
    intercept[IllegalArgumentException] {
      collectAndValidate(df, json_diff_col_order, "validator_diff_schema.json")
    }

    // Different values
    intercept[IllegalArgumentException] {
      collectAndValidate(df.sort($"a_i".desc), json, "validator_diff_values.json")
    }
  }
  //测试箭头验证器
  test("roundtrip payloads") {
    val inputRows = (0 until 9).map { i =>
      InternalRow(i)
    } :+ InternalRow(null)

    val schema = StructType(Seq(StructField("int", IntegerType, nullable = true)))

    val ctx = TaskContext.empty()
    val payloadIter = ArrowConverters.toPayloadIterator(inputRows.toIterator, schema, 0, ctx)
    val outputRowIter = ArrowConverters.fromPayloadIterator(payloadIter, ctx)

    assert(schema.equals(outputRowIter.schema))

    var count = 0
    outputRowIter.zipWithIndex.foreach { case (row, i) =>
      if (i != 9) {
        assert(row.getInt(0) == i)
      } else {
        assert(row.isNullAt(0))
      }
      count += 1
    }

    assert(count == inputRows.length)
  }

  /** Test that a converted DataFrame to Arrow record batch equals batch read from JSON file */
  private def collectAndValidate(df: DataFrame, json: String, file: String): Unit = {
    // NOTE: coalesce to single partition because can only load 1 batch in validator
    val arrowPayload = df.coalesce(1).toArrowPayload.collect().head
    val tempFile = new File(tempDataPath, file)
    Files.write(json, tempFile, StandardCharsets.UTF_8)
    validateConversion(df.schema, arrowPayload, tempFile)
  }

  private def validateConversion(
      sparkSchema: StructType,
      arrowPayload: ArrowPayload,
      jsonFile: File): Unit = {
    val allocator = new RootAllocator(Long.MaxValue)
    val jsonReader = new JsonFileReader(jsonFile, allocator)

    val arrowSchema = ArrowUtils.toArrowSchema(sparkSchema)
    val jsonSchema = jsonReader.start()
    Validator.compareSchemas(arrowSchema, jsonSchema)

    val arrowRoot = VectorSchemaRoot.create(arrowSchema, allocator)
    val vectorLoader = new VectorLoader(arrowRoot)
    val arrowRecordBatch = arrowPayload.loadBatch(allocator)
    vectorLoader.load(arrowRecordBatch)
    val jsonRoot = jsonReader.read()
    Validator.compareVectorSchemaRoot(arrowRoot, jsonRoot)

    jsonRoot.close()
    jsonReader.close()
    arrowRecordBatch.close()
    arrowRoot.close()
    allocator.close()
  }
}
