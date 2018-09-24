package com.spark.aimer.debug.compare

import java.sql.Timestamp
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

/**
  * Created by Aimer1027 on 2018/9/24.
  */
object SparkStructuredImplement {

  case class MyStuItem( gradeID:Long,
                            classID:Long,
                            studentID:Long,
                            score:Int,
                            timestamp:Timestamp)

  def rddStrToStuItem(rddStr:String):MyStuItem = {
    val lines = rddStr.trim.split(",")
    if ( lines.length < 5) Nil
    val gradeID = lines(0).toLong
    val classID = lines(1).toLong
    val studentID = lines(2).toLong
    val score   = lines(3).toInt
    val timestamp = Timestamp.valueOf(lines(4).toString)

    MyStuItem(gradeID, classID, studentID, score, timestamp)
  }


  def groupByKeyFunc(item:MyStuItem) = {
    val key = (item.gradeID, item.classID)
    key
  }


  def main(args:Array[String]) = {
    val topic = "dasou-stream"
    val brokers = ""
    val groupId = "kafka-group-aimer-20180924"

    val sparkSession = SparkSession.builder().appName("SparkStructuredImpl").getOrCreate()

    import sparkSession.implicits._
    val streamLine = sparkSession.readStream.
      format("kafka").
      option("kafka.bootstrap.servers", brokers).
      option("startingOffsets", "latest").
      option("auto.offset.rest", "latest").
      option("subscribe", topic).load.select($"value").
      as[(String)].
      map(rddStrToStuItem)

    val query = streamLine.groupByKey(groupByKeyFunc).
      flatMapGroups{ case (key, iter) => {
         var latestMyStuItem:MyStuItem = iter.next()
         while ( iter.hasNext ) {
           val curMyStuItem = iter.next()
           if ( latestMyStuItem.timestamp.after(curMyStuItem.timestamp) ) {
             latestMyStuItem = curMyStuItem
           }
         }
        Iterator(latestMyStuItem)
    }}.
      writeStream.
      format("console").
      trigger(Trigger.ProcessingTime(10L, TimeUnit.SECONDS)).
      outputMode(OutputMode.Complete).
      queryName("SparkStructuredImpl-Query").
      start

    query.awaitTermination()
  }
}