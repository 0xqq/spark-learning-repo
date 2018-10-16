package com.spark.aimer.structured.sink

import java.io.{BufferedInputStream, FileInputStream, ObjectInputStream, ObjectOutputStream}
import java.util.concurrent.TimeUnit

import com.alibaba.fastjson.{JSON, JSONObject}
import com.spark.aimer.structured.sink.KafkaSourceToHdfsSink.HdfsData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FSDataOutputStream, FileSystem, Path}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{ForeachWriter, Row, SparkSession}
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

import scala.concurrent.duration.Duration

/**
  * 本 App 主要测试将数据流从 kafka 中进行加载进行简单过滤
  * 以 30s 执行一次数据聚合, 1 分钟通过 Trigger 写入一次数据
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
  * 1. /app/xxx/${timestamp}/filename.{csv|json|parquet}
  * 2. /app/xxx/${timestamp}.{csv|json|parquet}
  * 两种格式的 FileStreamSink 方法写入数据
  **/

object KafkaSourceToHdfsSink extends Logging {

  def rddStrToKafkaMsgBeam(rddStr:String)= {
    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject])
    val id = jsonObj.getString("id")
    val msg = jsonObj.getString("msg")
    val timestamp = jsonObj.getString("timestamp")
    (id, msg, timestamp)
  }

  case class HdfsData(id:String, msg:String, timestamp:String)

  def main(args:Array[String]) = {
    initializeLogIfNecessary(true)

    val kafkaTopic = "dasou-stream"
    val broker = "broker-list"
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
      format("csv").
      outputMode(OutputMode.Complete).
      option("checkpointLocation", "/").
      option("path", "hdfs://path").
      trigger(Trigger.ProcessingTime(30L, TimeUnit.SECONDS)).
      start
  }
}


