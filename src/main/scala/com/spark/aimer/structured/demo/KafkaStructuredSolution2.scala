package com.spark.aimer.structured.demo

import java.sql.Timestamp
import java.util.concurrent.TimeUnit

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.{OutputMode, Trigger}

/**
  * Created by Aimer1027 on 2018/9/22.
  *
  *
  * In this class(object) we try to use the functions provided by strucured streaming
  * to implement the
  * group by
  * and
  * order by
  *
  * By following steps
  *
  * 1. first step, we use the groupByKey and set the key = (partition key words)
  *    in this way , we can group the datasets which has the same key in same group
  *
  *    And the DataSet transfers from Dataset[MyItem] to Dataset[(partition(key-words), Iterator[MyItem])]
  *
  * 2. second step, we use flatMapGroups in which we could sort Iterator[MyItem] by MyItem.INSTIME
  *    which used as the key word in order by SQL Command,
  *    and after sorting taking the first element from the Iterator[MyItem]
  *    and this element is the earliest timestamp MyItem in current group
  *
  *    After doing this we can archive the earliest timestamp MyItem from each group in which the key are the same
  *
  *    And the DataSet transfer from Dataset[(partition/group (key-words)), Iterator[MyItem]] to
  *    Dataset[Iterator[MyItem]]
  *
  * 3. last step, we traverse each group and take the MyItem which contains the whole value (like the select * from xxx)
  *    and output it to console
  *
  * 4. And these series of operations make that the final object is a streaming = true instance ,
  *    so we can use the writeStream && trigger to compute and generate the results periodically
  *
  * Not test in spark-shell yet, some logic needs to modify
  *
  * References: https://stackoverflow.com/questions/46603430/spark-structured-streaming-how-to-deduplicate-by-latest-and-aggregate-count
  */
object KafkaStructuredSolution2 {

  case class MyItem(LINENO: Int,
                    STATIONID: Int,
                    INSTINE: java.sql.Timestamp,
                    UPPASSENGER: Int,
                    DOWNPASSENGER: Int,
                    ISUPDOWN: Int,
                    LABELNO: Int
                   )

  def rddStrToMyItem(rddStr: String) = {
    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject]).getJSONObject("data")

    val LINENO = jsonObj.getIntValue("LINENO")
    val STATIONID = jsonObj.getIntValue("ISUPDOWN")
    val INSTINE = Timestamp.valueOf(jsonObj.getString("INSTINE"))
    val UPPASSENGER = jsonObj.getIntValue("UPPASSENGER")
    val DOWNPASSENGER = jsonObj.getIntValue("DOWNPASSENGER")
    val ISUPDOWN = jsonObj.getIntValue("STATIONID")
    val LABELNO = jsonObj.getIntValue("LABELNO")

    MyItem(LINENO, STATIONID, INSTINE, UPPASSENGER, DOWNPASSENGER, ISUPDOWN, LABELNO)
  }

  def rddStrToTuple(rddStr: String) = {

    val jsonObj = JSON.parseObject(rddStr, classOf[JSONObject]).getJSONObject("data")

    val LINENO = jsonObj.getIntValue("LINENO")
    val STATIONID = jsonObj.getIntValue("ISUPDOWN")
    val INSTINE = Timestamp.valueOf(jsonObj.getString("INSTINE"))
    val UPPASSENGER = jsonObj.getIntValue("UPPASSENGER")
    val DOWNPASSENGER = jsonObj.getIntValue("DOWNPASSENGER")
    val ISUPDOWN = jsonObj.getIntValue("STATIONID")
    val LABELNO = jsonObj.getIntValue("LABELNO")

    (LINENO, STATIONID, INSTINE, UPPASSENGER, DOWNPASSENGER, ISUPDOWN, LABELNO)
  }

  def gropuByKeyFunc(item: MyItem) = {
    val key = (item.LINENO, item.STATIONID, item.LABELNO)
    key
  }

  def main(args: Array[String]): Unit = {
    val topic = "dasou-stream"
    val brokers = "${kafka.bootstrap.servers}"
    val groupId = "kafka-groupId-aimer-20180922"

    // here we build the instance of SparkSession
    val sparkSession = SparkSession.builder().appName("StructuredStreamingSparkApp").getOrCreate()

    import sparkSession.implicits._
    val lines = sparkSession.readStream.
      format("kafka").
      option("kafka.bootstrap.servers", brokers).
      option("startingOffsets", "latest").
      option("auto.offset.rest", "latest").
      option("subscribe", topic).load.select($"value").as[(String)].
      map(rddStrToMyItem)
    /*.toDF(
    "LINENO", "STATIONID",
    "INSTINE", "UPPASSENGER",
    "DOWNPASSENGER",
    "ISUPDOWN", "LABELNO")
*/


    lines.groupByKey(gropuByKeyFunc).flatMapGroups { case (key, iter) => {
      var earliestMyItem = iter.next()
      while (iter.hasNext) {
        val curMyItem = iter.next()
        if (curMyItem.INSTINE.before(earliestMyItem.INSTINE)) {
          earliestMyItem = curMyItem
        }
      }
      Iterator(earliestMyItem)
    }
    }.
      writeStream.
      format("console").
      option("truncate", false).
      trigger(Trigger.ProcessingTime(10L, TimeUnit.SECONDS)).
      outputMode(OutputMode.Complete).
      queryName("Window-Operation-Demo").
      start
  }
}
