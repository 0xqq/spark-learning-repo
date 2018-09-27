package com.spark.aimer.structured.sink

import java.sql.Timestamp
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.streaming.{OutputMode}
import org.apache.spark.sql.SparkSession
import com.alibaba.fastjson.{JSON, JSONObject}


/**
  * Created by Aimer1027 on 2018/9/27.
  *
  * reference:
  */

object KafkaSinkSparkApp {

  case class MyItem(LINENO: Int,
                    STATIONID: Int,
                    INSTINE: java.sql.Timestamp,
                    UPPASSENGER: Int,
                    DOWNPASSENGER: Int,
                    ISUPDOWN: Int,
                    LABELNO: Int)

  def myItem2Str(x: MyItem): String = {
    s"[LINENO]=${x.LINENO},[ISUPDOWN]=${x.ISUPDOWN},[INSTINE]=${x.INSTINE},[UPPASSENGER]=${x.UPPASSENGER}," +
      s"[DOWNPASSENGER]=${x.DOWNPASSENGER}," +
      s"[STATIONID]=${x.STATIONID},[LABELNO]=${x.LABELNO}"
  }

  def rddStrToMyItem(rddStr: String) = {
    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject]).getJSONObject("data")

    val LINENO = jsonObj.getIntValue("LINENO")
    val STATIONID = jsonObj.getIntValue("ISUPDOWN")
    val INSTINE = Timestamp.valueOf(jsonObj.getString("INSTINE"))
    val UPPASSENGER = jsonObj.getIntValue("UPPASSENGER")
    val DOWNPASSENGER = jsonObj.getIntValue("DOWNPASSENGER")
    val ISUPDOWN = jsonObj.getIntValue("STATIONID")
    val LABELNO = jsonObj.getIntValue("LABELNO")
    val myItem = MyItem(LINENO, STATIONID, INSTINE, UPPASSENGER, DOWNPASSENGER, ISUPDOWN, LABELNO)
    myItem2Str(myItem)
  }

  def main(args: Array[String]) = {
    val inTopic = "${in-topic-name}"
    val outTopic = "${out-topic-name}"

    val brokers = "${kafka.bootstrap.servers}"
    val sparkSession = SparkSession.builder().appName("KafkaSinkSparkApp").getOrCreate()
    import sparkSession.implicits._
    val streamDF = sparkSession.readStream.
      format("kafka").
      option("kafka.bootstrap.servers", brokers).
      option("startingOffsets", "latest").
      option("auto.offset.rest", "latest").
      option("subscribe", inTopic).load.select($"value").
      as[(String)].
      map(rddStrToMyItem).toDF("value")

    // this can work
    streamDF.
      writeStream.format("kafka").
      option("topic", outTopic).
      option("kafka.bootstrap.servers", brokers).
      option("checkpointLocation", "hdfs://xxx/checkpoint/xx").
      outputMode(OutputMode.Append).
      start


    /*
       thi doesn't work
       streamDF.
       writeStream.format("kafka").
       option("topic", outTopic).
       option("kafka.bootstrap.servers", brokers).
       option("checkpointLocation", "file:///home/work/aimer/data").
       outputMode(OutputMode.Append).
       start

       here if we set the checkpoint path to local path with prefix: file:///
       Exception will be thrown, cause the checkpoint will use the HDFS API to find its path which set in conf/core-site.xml
       instead of local file system.

       java.lang.UnsatisfiedLinkError: org.apache.hadoop.util.NativeCrc32.nativeComputeChunkedSumsByteArray(II[BI[BIILjava/lang/String;JZ)V
  at org.apache.hadoop.util.NativeCrc32.nativeComputeChunkedSumsByteArray(Native Method)
  at org.apache.hadoop.util.NativeCrc32.calculateChunkedSumsByteArray(NativeCrc32.java:86)
  at org.apache.hadoop.util.DataChecksum.calculateChunkedSums(DataChecksum.java:430)
  at org.apache.hadoop.fs.FSOutputSummer.writeChecksumChunks(FSOutputSummer.java:202)
  at org.apache.hadoop.fs.FSOutputSummer.flushBuffer(FSOutputSummer.java:163)
  at org.apache.hadoop.fs.FSOutputSummer.flushBuffer(FSOutputSummer.java:144)
  at org.apache.hadoop.fs.ChecksumFileSystem$ChecksumFSOutputSummer.close(ChecksumFileSystem.java:412)
  at org.apache.hadoop.fs.FSDataOutputStream$PositionCache.close(FSDataOutputStream.java:72)
  at org.apache.hadoop.fs.FSDataOutputStream.close(FSDataOutputStream.java:106)
  at org.apache.commons.io.IOUtils.closeQuietly(IOUtils.java:303)
  at org.apache.commons.io.IOUtils.closeQuietly(IOUtils.java:274)
  at org.apache.spark.sql.execution.streaming.StreamMetadata$.write(StreamMetadata.scala:85)
  at org.apache.spark.sql.execution.streaming.StreamExecution$$anonfun$6.apply(StreamExecution.scala:116)
  at org.apache.spark.sql.execution.streaming.StreamExecution$$anonfun$6.apply(StreamExecution.scala:114)
  at scala.Option.getOrElse(Option.scala:121)
  at org.apache.spark.sql.execution.streaming.StreamExecution.<init>(StreamExecution.scala:114)
  at org.apache.spark.sql.streaming.StreamingQueryManager.createQuery(StreamingQueryManager.scala:240)
  at org.apache.spark.sql.streaming.StreamingQueryManager.startQuery(StreamingQueryManager.scala:278)
  at org.apache.spark.sql.streaming.DataStreamWriter.start(DataStreamWriter.scala:282)
  ... 56 elided
 */
  }

}
