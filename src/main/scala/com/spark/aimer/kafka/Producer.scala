package com.spark.aimer.kafka

import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import com.alibaba.fastjson.JSONObject
import com.spark.aimer.debug.KafkaProducerCallBack
import kafka.serializer.StringEncoder
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer


/**
  * Created by Aimer1027 on 2018/9/19.
  */
object Producer {
  def main(args: Array[String]): Unit = {
    val brokers = "kafka-broker-list"
    val topic = "dasou-in"
    val props = new Properties()
    props.put("metadata.broker.list", brokers)
    props.put("bootstrap.servers", brokers)
    props.put("serializer.class", classOf[StringEncoder])
    props.put("request.required.acks", "1")
    props.put("key.serializer", classOf[StringSerializer])
    props.put("value.serializer", classOf[StringSerializer])
    props.put("partitioner.class", classOf[com.spark.aimer.kafka.MyHashPartitioner])

    val kafkaProducer = new KafkaProducer[String, String](props)
    var id: Int = 1
    val timestampFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    while (true) {
      val kafkaIndexKey = s"${id}"
      val kafkaMsgStr = msg2Str(new KafkaMsgBean(kafkaIndexKey,
        "msg content", timestampFormat.format(new Date)))
      kafkaProducer.send(new ProducerRecord[String, String](topic, kafkaIndexKey, kafkaMsgStr),
        new KafkaProducerCallBack)
      Thread.sleep(1000)
      id += 1
    }

  }

  case class KafkaMsgBean(id: String, msg: String, timestamp: String)

  def msg2Str(kafkaMsgBean: KafkaMsgBean): String = {
    var json: JSONObject = new JSONObject()
    json.put("id", kafkaMsgBean.id)
    json.put("msg", kafkaMsgBean.msg)
    json.put("timestamp", kafkaMsgBean.timestamp)
    json.toString
  }
}
