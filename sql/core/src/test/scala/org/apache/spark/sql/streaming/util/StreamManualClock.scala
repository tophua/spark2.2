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

package org.apache.spark.sql.streaming.util

import org.apache.spark.util.ManualClock

/**
 * ManualClock used for streaming tests that allows checking whether the stream is waiting
 * on the clock at expected times.
  * 用于流测试的ManualClock,用于在预期的时间检查流是否正在等待时钟
 */
class StreamManualClock(time: Long = 0L) extends ManualClock(time) with Serializable {
  private var waitStartTime: Option[Long] = None
  private var waitTargetTime: Option[Long] = None

  override def waitTillTime(targetTime: Long): Long = synchronized {
    try {
      waitStartTime = Some(getTimeMillis())
      waitTargetTime = Some(targetTime)
      super.waitTillTime(targetTime)
    } finally {
      waitStartTime = None
      waitTargetTime = None
    }
  }

  /** Is the streaming thread waiting for the clock to advance when it is at the given time
    * 在给定的时间内,等待时钟前进的流线程是否在等待？ */
  def isStreamWaitingAt(time: Long): Boolean = synchronized {
    waitStartTime == Some(time)
  }

  /** Is the streaming thread waiting for clock to advance to the given time
    * 流线程等待时钟提前到给定的时间吗？*/
  def isStreamWaitingFor(target: Long): Boolean = synchronized {
    waitTargetTime == Some(target)
  }
}

