package com.spark.aimer.structured.sink

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

/**
  * 本 App 主要测试将数据流从 kafka 中进行加载进行简单过滤
  * 5秒钟通过 Trigger 写入一次数据
  * 将数据 sink 按照时间戳进行切割, 存放到 HDFS 指定路径下
  *
  * 不考虑复杂逻辑场景,且接收数据流为单 kafka topic,
  *
  * 上游输入元数据信息
  * 1. kafka brokers
  * 2. kafka topic
  *
  * 下游输入元数据信息,需要将下游数据根据时间戳进行区分
  * 分别测试两种文件生成格式:
  * 通过调用 format({csv/parquet|text})
  *
  * 1. /app/xxx/${timestamp}/filename.{csv|json|parquet}
  * 2. /app/xxx/${timestamp}.{csv|json|parquet}
  * 两种格式的 FileStreamSink 方法下游写入数据格式
  *
  * 1. 是否能够支持根据时间戳来切割文件
  * 2. 是否能够根据时间戳来生成对应路径下的文件
  * 详细文档描述, 参考 [sinker.md]()
  **/

object KafkaSourceToHdfsSink extends Logging {

  def rddStrToKafkaMsgBeam(rddStr:String)= {
    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject])
    val id = jsonObj.getString("id")
    val msg = jsonObj.getString("msg")
    val timestamp = jsonObj.getString("timestamp")
    (id, msg, timestamp)
  }

  def get_timestamp():String = {
    val timestampFormat: SimpleDateFormat = new SimpleDateFormat("yyyyMMddHHmmss")
    val timestampStr = timestampFormat.format(new Date)
    timestampStr
  }

  case class HdfsData(id:String, msg:String, timestamp:String)

  def main(args:Array[String]) = {
    val kafkaTopic = "dasou-stream"
    val broker = ""
    val spark = SparkSession.builder().appName("KafkaSourceToHdfsSink").getOrCreate()
    import spark.implicits._
    val kafkaDF = spark.
      readStream.
      format("kafka").
      option("kafka.bootstrap.servers", broker).
      option("startingOffsets", "latest").
      option("auto.offset.reset", "latest").
      option("subscribe", kafkaTopic).
      load.
      select($"value").
      as[(String)].
      map(rddStrToKafkaMsgBeam).toDF("id", "msg", "timestamp")

    kafkaDF.printSchema()

    kafkaDF.
      writeStream.
      format("parquet").
      option("checkpointLocation", "/app/business/haichuan/cbc/aimer/").
      option("path", s"/app/business/haichuan/cbc/aimer/spark_output_data/${get_timestamp}").
      trigger(Trigger.ProcessingTime(5L, TimeUnit.SECONDS)).
      start
  }
}


