package com.spark.aimer.debug

import java.util

import org.apache.kafka.clients.producer.Partitioner
import org.apache.kafka.common.Cluster;

/**
  * Created by Aimer1027 on 2018/9/18.
  */
class KafkaDefaultPartitioner extends Partitioner {

  override def close(): Unit = {}

  override def partition(topic: String, key: scala.Any, keyBytes: Array[Byte],
                         value: scala.Any, valueBytes: Array[Byte],
                         cluster: Cluster): Int = {
    val partitionNum = cluster.partitionCountForTopic(topic)
    var partitionId = 2
    if (key.isInstanceOf[Integer]) {
      partitionId = Integer.getInteger(key.toString) % partitionNum
    } else {
      partitionId = key.hashCode() % partitionNum
    }
    partitionId
  }

  override def configure(map: util.Map[String, _]): Unit = {
  }
}
