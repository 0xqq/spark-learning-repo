package com.spark.aimer.debug

import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import kafka.serializer.StringEncoder
import net.sf.json.JSONObject
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

/**
  * Created by Aimer1027 on 2018/9/18.
  */
object KafkaProducer {

  def main(args: Array[String]): Unit = {
    val brokers = "10.99.217.95:8092,10.99.217.101:8092,10.99.199.26:8092,10.99.199.27:8092,10.99.199.25:8092"
    val topic = "dasou-stream"
    val props = new Properties()
    props.put("metadata.broker.list", brokers)
    props.put("bootstrap.servers", brokers)
    props.put("serializer.class", classOf[StringEncoder])
    props.put("request.required.acks", "1")
    props.put("key.serializer", classOf[StringSerializer])
    props.put("value.serializer", classOf[StringSerializer])
    props.put("partitioner.class", classOf[KafkaDefaultPartitioner])

    val kafkaProducer = new KafkaProducer[String, String](props)

    var total = 100000
    for ( kafka_msg_key <- 1 to total ) {
      val eventJsonObj = new JSONObject()
      val eventJsonObj1 = new JSONObject()

      // build data
      val m1 = 2
      val m2 = 1
      val m3 = 1
      val m4 = 1
      val m5 = 1
      val m6 = 1

      eventJsonObj.put("LINENO", m1.toString)
      eventJsonObj.put("ISUPDOWN", m2.toString)
      eventJsonObj.put("LABELNO", m1.toString)
      eventJsonObj.put("STATIONID", m1.toString)
      eventJsonObj.put("UPPASSENGER", m1.toString)
      eventJsonObj.put("DOWNPASSENGER", m1.toString)
      eventJsonObj.put("INSTINE", NowDate().toString)


      eventJsonObj1.put("V", "V7")
      eventJsonObj1.put("data", eventJsonObj)
      kafkaProducer.send(new ProducerRecord[String, String](topic, s"${kafka_msg_key}", eventJsonObj1.toString),
        new KafkaProducerCallBack())
      Thread.sleep(100)
      println(s"send data count = ${kafka_msg_key} to topic = ${topic}")
    }

    def NowDate(): String = {
      val now: Date = new Date()
      val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val date = dateFormat.format(now)
      return date
    }

    //日期
    def datetime(): String = {
      var i = 0
      var b = ""
      while (i < 1000) {
        i = i + 1
        //天
        val day = (Math.random() * 21 + 10)
        val dd = day.formatted("%.0f")
        //println(dd)
        //小时
        val xx = (Math.random() * 14 + 10)
        val xs = xx.formatted("%.0f")
        //println(xs)
        //分钟
        val ff = (Math.random() * 49 + 10)
        val fz = ff.formatted("%.0f")
        //    println(fz)
        //秒钟
        val mm = (Math.random() * 49 + 10)
        val mz = mm.formatted("%.0f")
        // println(mz)
        //println("201808"+dd+xs+fz+mz)
        b = "2018-08-" + dd + " " + xs + ":" + fz + ":" + mz
      }
      b
    }

  }
}
