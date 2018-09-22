package com.spark.aimer.structured

import java.util.concurrent.TimeUnit

import org.apache.spark.sql.streaming.{OutputMode, ProcessingTime, Trigger}
import org.apache.spark.sql.SparkSession
import com.alibaba.fastjson.{JSON, JSONObject}

/**
  * Created by Aimer1027 on 2018/9/21.
  *
  * Here is the solution for spark-streaming DStream memory
  */
object KafkaStructuredSolution1 {

  def dataParser(rddStr: String) = {

    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject]).getJSONObject("data")

    val LINENO = jsonObj.getIntValue("LINENO")
    val STATIONID = jsonObj.getIntValue("ISUPDOWN")
    val INSTINE = jsonObj.getString("INSTINE")
    val UPPASSENGER = jsonObj.getIntValue("UPPASSENGER")
    val DOWNPASSENGER = jsonObj.getIntValue("DOWNPASSENGER")
    val ISUPDOWN = jsonObj.getIntValue("STATIONID")
    val LABELNO = jsonObj.getIntValue("LABELNO")

    (LINENO, STATIONID, INSTINE, UPPASSENGER, DOWNPASSENGER, ISUPDOWN, LABELNO)
  }

  def main(args: Array[String]): Unit = {
    val topic = "${set your kafka topic here}"
    val brokers = "${set your kafka.bootstrap.servers here }"
    val group = "${set your group.id here}"

    // here we build the instance of SparkSession
    val sparkSession = SparkSession.builder().appName("StructuredStreamingSparkApp").getOrCreate()

    import sparkSession.implicits._
    val dataFrame = sparkSession.readStream.
      format("kafka").
      option("kafka.bootstrap.servers", brokers).
      option("startingOffsets", "latest").
      option("auto.offset.rest", "latest").
      option("subscribe", topic).load.select($"value").as[(String)].
      map(dataParser).toDF(
      "LINENO", "STATIONID",
      "INSTINE", "UPPASSENGER", "DOWNPASSENGER",
      "ISUPDOWN", "LABELNO")

    // here we create view of dataFrame for temporary
    val tempViewName = "AimerTempView"
    dataFrame.createOrReplaceTempView(tempViewName)

    val sqlCmd =
      """
         |SELECT LINENO,ISUPDOWN,LABELNO,STATIONID,SUM(UPPASSENGER) UPSum,
         |SUM(DOWNPASSENGER) DOWNSum from AimerTempView
         |WHERE ISUPDOWN IN (0,1)
         |AND UPPASSENGER < 100
         |AND DOWNPASSENGER < 100
         |GROUP BY LINENO, ISUPDOWN,LABELNO, STATIONID
      """.stripMargin

    sparkSession.sql(sqlCmd).writeStream.
      format("console").
      option("truncate", false).
      trigger(Trigger.ProcessingTime(10L, TimeUnit.SECONDS)).
      outputMode(OutputMode.Complete).
      queryName("query-kafka-streaming-solution2").
      start()
      .awaitTermination()
  }
}
