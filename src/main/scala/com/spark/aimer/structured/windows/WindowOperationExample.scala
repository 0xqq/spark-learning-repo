package com.spark.aimer.structured.windows

import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
  * In this class we will use the RateStreamSource which underlying generating data by RateSourceProvider
  * to replace KafkaSourceProvide to generated upstream data.
  *
  * References:
  ** RateProvider:
  * https://jaceklaskowski.gitbooks.io/spark-structured-streaming/content/spark-sql-streaming-RateStreamSource.html
  ** WindowExample:
  * https://github.com/apache/spark/blob/master/examples/src/main/scala/org/apache/spark/examples/sql/streaming/StructuredNetworkWordCountWindowed.scala
  **/

/**
  * here is the output results executed the following codes in spark shell by setup commands:
  *
  * ./bin/spark-shell --master local[*]
  *
  * -------------------------------------------
  * Batch: 0
  * -------------------------------------------
  * +---------------------------------------------+-----+-----+
  * |window                                       |value|count|
  * +---------------------------------------------+-----+-----+
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|3    |1    |
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|5    |1    |
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|4    |1    |
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|0    |1    |
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|1    |1    |
  * |[2018-11-02 17:05:50.0,2018-11-02 17:05:58.0]|2    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|5    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|2    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|8    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|7    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|6    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|3    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|0    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|1    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|9    |1    |
  * |[2018-11-02 17:05:51.0,2018-11-02 17:05:59.0]|4    |1    |
  * |[2018-11-02 17:05:52.0,2018-11-02 17:06:00.0]|8    |1    |
  * |[2018-11-02 17:05:52.0,2018-11-02 17:06:00.0]|7    |1    |
  * |[2018-11-02 17:05:52.0,2018-11-02 17:06:00.0]|9    |1    |
  * |[2018-11-02 17:05:52.0,2018-11-02 17:06:00.0]|4    |1    |
  * +---------------------------------------------+-----+-----+
  *
  * And in our example stream, we set the upstream generated 10 rows per seconds.
  *
  * And set window attributes:
  *
  * - window duration(range of the window generated every time) = 10 seconds
  * - window slide(every time the window with the length of the window duration move forward steps) = 2 seconds
  **/

object WindowOperationExample {

  def man(args:Array[String]) = {

    // here we build the spark session instance
    val spark = SparkSession.builder.appName("StructedStreamingWindowExample").getOrCreate

    val rowPerSecond:Int = 10
    // upstream data is generated 10 rows per seconds, with the schema shown as below:
    // root
    // |-- timestamp: timestamp (nullable = true)
    // |-- value: long (nullable = true)

    val windowDuration = "10 seconds"
    val slideDuration = "2 seconds"

    val rateDF = spark.
      readStream.
      format("rate").
      option("rowPerSecond", rowPerSecond).
      load


    import spark.implicits._
    val windowedCounts = rateDF.
      groupBy(
        window($"timestamp", windowDuration, slideDuration),
        $"value"
      ).count.
      orderBy("window")

    // Start running the query that prints the windowed word counts to the console
    val query = windowedCounts.
      writeStream.
      outputMode("complete").
      option("truncate", "false").
      start

    query.awaitTermination
  }
}
