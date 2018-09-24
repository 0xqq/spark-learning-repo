package com.spark.aimer.debug.compare

import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import com.alibaba.fastjson.JSONObject
import com.spark.aimer.debug.{KafkaDefaultPartitioner, KafkaProducerCallBack}
import kafka.serializer.StringEncoder
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer

import scala.util.Random

/**
  * Created by Aimer1027 on 2018/9/18.
  */
object StuDataKafkaProducer {

  def main(args: Array[String]): Unit = {
    val brokers = "${set your own kafka.bootstrap.servers here}"
    val topic = "${set your own topic here}"
    val props = new Properties()
    props.put("metadata.broker.list", brokers)
    props.put("bootstrap.servers", brokers)
    props.put("serializer.class", classOf[StringEncoder])
    props.put("request.required.acks", "1")
    props.put("key.serializer", classOf[StringSerializer])
    props.put("value.serializer", classOf[StringSerializer])
    props.put("partitioner.class", classOf[KafkaDefaultPartitioner])

    val kafkaProducer = new KafkaProducer[String, String](props)

    // you can modify this value to control everytime send different number of messages to kafka cluster  
    var total = 100000
    for ( kafka_msg_key <- 1 to total ) {
      val eventJsonObj = new JSONObject()
      val eventJsonObj1 = new JSONObject()

       // gradeID, classID, studentID, score, timestamp
      val dataStr = s"${gradeID},${classID},${studentID},${score},${timestamp}"

      kafkaProducer.send(new ProducerRecord[String, String](topic, s"${kafka_msg_key}", dataStr),
        new KafkaProducerCallBack())
      Thread.sleep(1000)
      println(s"send data count = ${kafka_msg_key} to topic = ${topic}")
    }

    def NowDate(): String = {
      val now: Date = new Date()
      val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val date = dateFormat.format(now)
      return date
    }



    def getRandomFrom0(random:Int, containZero:Boolean):String = {
      var tempRandom = new Random().nextInt(random)
      if ( !containZero ) {
        tempRandom += 1
      }
      s"${tempRandom}"
    }


    // here is the gradeID for student
    // gradeID: from 1 to  2, not contain 0
    def gradeID():String = {
       getRandomFrom0(2, false)
    }

    // here is the classID for student
    // classID: {1-3}
    def classID():String = {
      s"${getRandomFrom0(3, false)}"
    }

    // here is the studentID for student
    // studentID: {1-9}{0-9}{0-9}{0-9}
    def studentID():String = {
      s"${getRandomFrom0(9,false)}${getRandomFrom0(9,true)}${getRandomFrom0(9,true)}${getRandomFrom0(9, true)}"
    }

    // here is the score for student
    // score: 0-100
    def score():String = {
      s"${getRandomFrom0(100, true)}"
    }


    // here is the timestamp for student streaming data
    def timestamp(): String = {
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
