package com.spark.aimer.exactlyonce.zk

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.kafka.common.TopicPartition
import org.apache.spark.streaming.kafka010.OffsetRange

/**
  * Created by Aimer1027 on 2018/10/1.
  */
object ZkPartitionOffsetParser {

  def fromTopicPartition(topicPartition: TopicPartition): String = {
    val jsonObj = new JSONObject
    jsonObj.put("topic", topicPartition.topic)
    jsonObj.put("partition", topicPartition.partition)

    jsonObj.toString
  }

  def toTopicPartition(str: String): TopicPartition = {
    val jsonObj: JSONObject = JSON.parseObject(str, classOf[JSONObject])
    val topic: String = jsonObj.getString("topic")
    val partition: Int = jsonObj.getInteger("partition")
    new TopicPartition(topic, partition)
  }

  def fromOffsetRange(offsetRange: OffsetRange): String = {
    val jsonObj = new JSONObject
    jsonObj.put("topic", offsetRange.topic)
    jsonObj.put("patition", offsetRange.partition)
    jsonObj.put("fromOffset", offsetRange.fromOffset)
    jsonObj.put("untilOffset", offsetRange.untilOffset)
    jsonObj.toString
  }

  def toOffsetRange(str: String): OffsetRange = {
    // create(topic : scala.Predef.String, partition : scala.Int, fromOffset : scala.Long, untilOffset : scala.Long)
    val jsonObj: JSONObject = JSON.parseObject(str, classOf[JSONObject])
    val topic: String = jsonObj.getString("topic")
    val partition: Int = jsonObj.getInteger("partition")
    val fromOffset: Long = jsonObj.getLong("fromOffset")
    val untilOffset: Long = jsonObj.getLong("untilOffset")
    new OffsetRange(topic, partition, fromOffset, untilOffset)
  }

}
