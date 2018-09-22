package com.spark.aimer.structured

import java.util.concurrent.TimeUnit

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.streaming.Seconds
import scala.concurrent.duration.Duration

/**
  * Created by Aimer1027 on 2018/9/20.
  * spark && kafka stream demo
  *
  * reference: https://jaceklaskowski.gitbooks.io/spark-structured-streaming/content/spark-sql-streaming-KafkaSourceProvider.html
  */
object KafkaStructuredStreamApp {
  def main(argsh: Array[String]): Unit = {
    val brokers = "${set your broker here}"
    val topic = "${set your topic here}"

    val sparkSession = SparkSession.builder().appName("StructuredKafkaStreamApp").getOrCreate()
    import sparkSession.implicits._
    val df = sparkSession.readStream.format("kafka")
      .option("kafka.bootstrap.servers", brokers)
      .option("startingOffsets", "latest")
      .option("auto.offset.reset", "latest")
      .option("subscribe", topic).load

    val frame =
      df.select(
        $"key" cast "string",
        $"value" cast "string",
        $"topic",
        $"partition",
        $"offset")

    import org.apache.spark.sql.streaming.OutputMode
    import org.apache.spark.sql.streaming.Trigger
    import java.util.concurrent.TimeUnit

    val sq = frame.writeStream.
      format("console").
      option("truncate", false).
      trigger(Trigger.ProcessingTime(10L, TimeUnit.SECONDS)).
      outputMode(OutputMode.Append).
      queryName("query-from-kafka-stream").
      start()
  }
}
