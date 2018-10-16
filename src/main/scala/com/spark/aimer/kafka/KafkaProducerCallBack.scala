package com.spark.aimer.kafka

import org.apache.kafka.clients.producer.RecordMetadata

/**
  * Created by Aimer1027 on 2018/9/30.
  */
object KafkaProducerCallBack extends org.apache.kafka.clients.producer.Callback {
  override def onCompletion(recordMetadata: RecordMetadata, e: Exception): Unit = {
    if (recordMetadata != null && e == null) {
      val topic = recordMetadata.topic()
      val partitionId = recordMetadata.partition()
      val offset = recordMetadata.offset()
      println(s"[Topic]=${topic}, [parition]=${partitionId}, [offseet]=${offset} send success")
    } else {
      println(s"message send failed with exception=${e.fillInStackTrace()}")
    }
  }
}
