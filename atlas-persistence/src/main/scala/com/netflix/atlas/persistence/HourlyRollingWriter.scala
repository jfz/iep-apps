/*
 * Copyright 2014-2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.persistence

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import com.netflix.atlas.core.model.Datapoint
import com.netflix.spectator.api.Registry
import com.typesafe.scalalogging.StrictLogging

/**
  * Hourly writer does hourly directory rolling, and delegates actual writing to underlying
  * RollingFileWriter.
  */
class HourlyRollingWriter(
  dataDir: String,
  maxLateDuration: Long,
  writerFactory: String => RollingFileWriter,
  registry: Registry
) extends StrictLogging {

  private val msOfOneHour = 3600000

  private val baseId = registry.createId("persistence.outOfOrderEvents")
  private val lateEventsCounter = registry.counter(baseId.withTag("id", "late"))
  private val futureEventsCounter = registry.counter(baseId.withTag("id", "future"))

  private var currWriterInfo: WriterInfo = _
  private var prevWriterInfo: WriterInfo = _

  // Assume maxLateDuration is within 1h
  require(maxLateDuration > 0 && maxLateDuration <= msOfOneHour)

  def initialize(): Unit = {
    currWriterInfo = createWriterInfo(System.currentTimeMillis())
    // Create Writer for previous hour if still within limit
    if (System.currentTimeMillis() <= maxLateDuration + currWriterInfo.startTime) {
      prevWriterInfo = createWriterInfo(currWriterInfo.startTime - msOfOneHour)
    }
  }

  def close(): Unit = {
    if (currWriterInfo != null) currWriterInfo.writer.close()
    if (prevWriterInfo != null) prevWriterInfo.writer.close()
  }

  private def rollOverWriter(): Unit = {
    if (prevWriterInfo != null) prevWriterInfo.writer.close
    prevWriterInfo = currWriterInfo
    currWriterInfo = createWriterInfo(System.currentTimeMillis())
  }

  private def createWriterInfo(ts: Long): WriterInfo = {
    val hourStart = getHourStart(ts)
    val hourEnd = hourStart + msOfOneHour
    val writer = writerFactory(getFilePathPrefixForHour(hourStart))
    writer.initialize
    WriterInfo(writer, hourStart, hourEnd)
  }

  def write(dp: Datapoint): Unit = {
    val now = System.currentTimeMillis()
    checkHourRollover(now)
    checkPrevHourExpiration(now)

    if (RollingFileWriter.RolloverCheckDatapoint eq dp) {
      //check rollover for both writers
      currWriterInfo.write(dp)
      if (prevWriterInfo != null) prevWriterInfo.write(dp)
    } else {
      // Range checking in order, higher possibility goes first:
      //   current hour -> previous hour -> late -> future
      val ts = dp.timestamp
      if (currWriterInfo.inRange(ts)) {
        currWriterInfo.write(dp)
      } else if (prevWriterInfo != null && prevWriterInfo.inRange(ts)) {
        prevWriterInfo.write(dp)
      } else if (ts < currWriterInfo.startTime) {
        lateEventsCounter.increment()
        logger.debug(s"found late event: $dp")
      } else {
        futureEventsCounter.increment()
        logger.debug(s"found future event: $dp")
      }
    }
  }

  private def checkHourRollover(now: Long) = {
    if (now >= currWriterInfo.endTime) {
      rollOverWriter()
    }
  }

  // Note: late arrival is only checked cross hour, not rolling time
  private def checkPrevHourExpiration(now: Long) = {
    if (prevWriterInfo != null && (now > currWriterInfo.startTime + maxLateDuration)) {
      logger.debug(s"stop writer for previous hour after maxLateDuration of $maxLateDuration ms")
      prevWriterInfo.writer.close
      prevWriterInfo = null
    }
  }

  private def getHourStart(timestamp: Long): Long = {
    timestamp / msOfOneHour * msOfOneHour
  }

  private def getFilePathPrefixForHour(hourStart: Long): String = {
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(hourStart), ZoneOffset.UTC)
    s"$dataDir/${dateTime.format(HourlyRollingWriter.HourFormatter)}"
  }

  case class WriterInfo(
    writer: RollingFileWriter,
    startTime: Long,
    endTime: Long
  ) {

    def write(dp: Datapoint): Unit = {
      writer.write(dp)
    }

    def inRange(ts: Long): Boolean = {
      ts >= startTime && ts < endTime
    }
  }
}

object HourlyRollingWriter {
  val HourFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")
  val HourStringLen: Int = 10
}