package com.spark.aimer.debug.compare

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
                            timestamp:java.sql.Timestamp)


  implicit object MyStuItemOrdeing extends Ordering[MyStuItem] {
    override def compare(x: MyStuItem, y: MyStuItem): Int = {
      y.timestamp.compareTo(x.timestamp)
    }
  }


  def rddStrToStuItem(rddStr:String):MyStuItem = {
    val lines = rddStr.trim.split(",")
    if ( lines.length < 5) Nil
    val gradeID = lines(0).toLong
    val classID = lines(1).toLong
    val studentID = lines(2).toLong
    val score   = lines(3).toInt
    val timestamp = java.sql.Timestamp.valueOf(lines(4).toString)

    MyStuItem(gradeID, classID, studentID, score, timestamp)
  }


  def groupByKeyFunc(item:MyStuItem) = {
    val key = (item.gradeID, item.classID)
    key
  }

  def main(args:Array[String]) = {
    val topic = "dasou-stream"
    val groupId = "kafka-group-aimer-20180924"
    val brokers = ""

    val sparkSession = SparkSession.builder().appName("SparkStructuredImpl").getOrCreate()

    import sparkSession.implicits._
    val streamLine = sparkSession.readStream.
      format("kafka").
      option("kafka.bootstrap.servers", brokers).
      option("startingOffsets", "latest").
      option("auto.offset.rest", "latest").
      option("subscribe", topic).load.select($"value").
      as[(String)].
      map(rddStrToStuItem).filter(item => item != Nil )

    // NOTE HERE: Complete OutputMode does not support no streaming aggregations on streaming DataFrames
    // We met exceptions like this :
    // org.apache.spark.sql.AnalysisException: Complete output mode not supported when there are no streaming aggregations on streaming DataFrames/Datasets;
    // So, We update the OutputMode.Complete to OutputMode.Update

    val query = streamLine.groupByKey(groupByKeyFunc).
      flatMapGroups{ case (key, iter) => {
        iter.toSeq.sortBy[MyStuItem](r => r).toIterator.take(3)
    }}.
      writeStream.
      format("console").
      trigger(Trigger.ProcessingTime(10L, TimeUnit.SECONDS)).
      outputMode(OutputMode.Append).
      queryName("SparkStructuredImpl-Query").
      start

    query.awaitTermination()
  }
}