package com.spark.aimer.exactlyonce.kafka

import java.util

import org.apache.kafka.clients.producer.Partitioner
import org.apache.kafka.common.Cluster

/**
  * Created by Aimer1027 on 2018/9/30.
  */
class MyHashPartitioner extends Partitioner {
  override def close(): Unit = {

  }

  override def partition(topic: String, key: scala.Any, keyBytes: Array[Byte],
                         value: scala.Any, valueBytes: Array[Byte],
                         cluster: Cluster): Int = {
    val partitionNum = cluster.partitionCountForTopic(topic)
    if (key.isInstanceOf[Integer]) {
      Integer.getInteger(key.toString) % partitionNum
    } else {
      key.hashCode() % partitionNum
    }
  }

  override def configure(map: util.Map[String, _]): Unit = {

  }
}
