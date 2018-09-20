package com.spark.aimer.debug

import scala.collection.mutable.ArrayBuffer
import net.sf.json.JSONObject
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.IntegerType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.streaming.{Milliseconds, Seconds, StreamingContext}
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent


/**
  * Created by Aimer1027 on 2018/9/18.
  *
  * This is the spark streaming application main entry class
  */
object KafkaDirectDemo {

  def dataArray(rddStr: String): ArrayBuffer[String] = {
    val JsonObject = JSONObject.fromObject(rddStr)
    val data = JsonObject.get("data").toString

    val JsonObject1 = JSONObject.fromObject(data)
    val LINENO: String = JsonObject1.get("LINENO").toString
    val STATIONID: String = JsonObject1.get("ISUPDOWN").toString
    val INSTINE: String = JsonObject1.get("INSTINE").toString
    val UPPASSENGER: String = JsonObject1.get("UPPASSENGER").toString
    val DOWNPASSENGER: String = JsonObject1.get("DOWNPASSENGER").toString
    val ISUPDOWN: String = JsonObject1.get("STATIONID").toString
    val LABELNO: String = JsonObject1.get("LABELNO").toString
    val array: ArrayBuffer[String] = new ArrayBuffer[String]()
    array.append(LINENO)
    array.append(STATIONID)
    array.append(INSTINE)
    array.append(UPPASSENGER)
    array.append(DOWNPASSENGER)
    array.append(ISUPDOWN)
    array.append(LABELNO)
    array
  }

  def main(args: Array[String]): Unit = {

    val topic = "${set your kafka.topic here}"
    val broker = "${set your kafka.bootstrap.servers here}"
    val group = "${set your group.id here}"

    // here we set the time interval which used to control batch interval
    val myBatch: Int = 10

    // set spark-streaming app name
    val conf = new SparkConf().setAppName("KafkaDirectDemo")

    // set streaming context graceful output
    conf.set("spark.streaming.stopGracefullyOnShutdown", "true")

    // set streaming context clean rdd and metadata when each batch finishes its computing
    conf.set("spark.streaming.unpersist", "true")

    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Seconds(myBatch))

    // here we set the streaming context clean its parent's dependencies every 3 milli-seconds
    // by setting this parameter , we can shorten the length lineage from current rdds to their parents
    ssc.remember(Milliseconds(3))

    // build sql context
    val sqlContext = SQLContext.getOrCreate(ssc.sparkContext)

    import sqlContext.implicits._

    // set kafka params
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> broker,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> group,
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    // set kafka topic for which the stream consume from
    val topics = Set(topic)

    // set rdd row, every time we store the last step rdd to this variable
    var lastRdd: RDD[Row] = null

    // build data stream from kafka to streaming context
    val stream: InputDStream[ConsumerRecord[String, String]] = KafkaUtils.createDirectStream[String, String](
      ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams))

    // stream's structure is key, value and some metadata, we use map to get the value from InputDStream
    val value: DStream[String] = stream.map(r => r.value())

    // transfer dstream to dataframe
    var r2 = value.foreachRDD { rdd => {
      val frame: DataFrame = rdd.map(rdd1 => dataArray(rdd1)).map(r => (r(0), r(1), r(2), r(3), r(4), r(5), r(6))).
        toDF("LINENO", "ISUPDOWN", "INSTINE", "UPPASSENGER", "DOWNPASSENGER", "STATIONID", "LABELNO")

      frame.select(frame("LINENO"), frame("ISUPDOWN").cast(IntegerType),
        frame("INSTINE"),
        frame("UPPASSENGER").cast(IntegerType),
        frame("DOWNPASSENGER").cast(IntegerType),
        frame("STATIONID").cast(IntegerType),
        frame("LABELNO").cast(IntegerType)
      )

      // here are three view name which will be used in sql
      val view1 = "AimerTest01"
      val view2 = "AimerTest02"
      val view3 = "AimerTest03"

      println(s"begin create view1=AimerTest01")
      frame.createOrReplaceTempView("AimerTest01")
      println(s"finish creating view1=AimerTest01")

      val sql =
        s"""
           |SELECT LINENO,ISUPDOWN,LABELNO,STATIONID,SUM(UPPASSENGER) UPS,SUM(DOWNPASSENGER) DOWNS from AimerTest01 WHERE
           |ISUPDOWN IN (0, 1)
           |AND UPPASSENGER < 100 AND DOWNPASSENGER < 100 GROUP BY LINENO,ISUPDOWN,LABELNO, STATIONID
        """.stripMargin

      println(s"begin execute sql=\n${sql}")
      val df = sqlContext.sql(sql)
      println(s"end execute sql=\n${sql}")

      println(s"begin create or replace view2=AimerTest02")
      df.createOrReplaceTempView(view2)
      println(s"end create or replace view2=AimerTest02")

      if (lastRdd == null) {

        lastRdd = df.rdd

        // every time we drop df
        df.drop()

        println(s"first time lastRdd is null, df show=${df.show(20)}, df total count=${df.count()}")

      } else {

        println(s"other time lastRdd is not null, df show=${lastRdd.count()}")

        println(s"begin create df from lastRdd transferring ")

        val df = lastRdd.map(r => (r(0).toString, r(1).toString, r(2).toString, r(3).toString, r(4).toString, r(5).toString))
          .toDF("LINENO", "ISUPDOWN", "LABELNO", "STATIONID", "UPS", "DOWNS")

        println(s"end transferring with df schema=${df.schema}, df show =${df.show(20)}")

        println(s"df begin select ")

        df.select(df("LINENO"), df("ISUPDOWN").cast(IntegerType), df("LABELNO"), df("STATIONID"),
          df("UPS").cast(IntegerType), df("DOWNS").cast(IntegerType))

        println(s"df end select df schema = ${df.schema}, df show = ${df.show(20)}")

        df.createOrReplaceTempView("AimerTest03")

        val sql2 =
          s"""
             |select NVL(t.LINENO,t1.LINENO) LINENO,NVL(t.ISUPDOWN,t1.ISUPDOWN) ISUPDOWN,
             |NVl(t.LABELNO,t1.LABELNO) LABELNO,NVL(t.STATIONID,t1.STATIONID) STATIONID,
             |SUM(NVL(t.UPS,0)+NVL(t1.UPS,0)) UPS,
             |SUM(NVL(t.DOWNS,0)+NVl(t1.DOWNS,0)) DOWNS
             |from AimerTest02 t full outer join AimerTest03 t1 on t.LINENO=t1.LINENO and t.STATIONID=t1.STATIONID
             |and t.LABELNO=t1.LABELNO and t.ISUPDOWN=t1.ISUPDOWN
             |GROUP BY NVL(t.LINENO,t1.LINENO),NVL(t.STATIONID,t1.STATIONID),NVl(t.LABELNO,t1.LABELNO),NVL(t.ISUPDOWN,t1.ISUPDOWN)
          """.stripMargin

        println(s"begin execute the sql2 =${sql2}")

        val df2 = sqlContext.sql(sql2)

        println(s"end execute the sql2=${sql2}")

        println("last result shown: ")

        lastRdd = df2.rdd

        println(s"get last rdd =${lastRdd.count()}")

        // drop the df
        df.drop()
      }
    }
    }
    ssc.start()
    ssc.awaitTermination()
  }
}
