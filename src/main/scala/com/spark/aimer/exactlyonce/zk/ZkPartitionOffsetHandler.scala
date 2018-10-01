package com.spark.aimer.exactlyonce.zk

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.kafka.common.TopicPartition
import org.apache.spark.streaming.kafka010.OffsetRange

/**
  * Created by Aimer1027 on 2018/10/1.
  *
  * We will organize the metadata of kafka offset in this structured:
  *
  * /${zkRootPath}/
  * -path(String): topic/${partition-id}
  * -value(metadata:String): RangeOffset.{topic:String, partition:Int, fromOffset:Long, untilOffset:Long}
  *
  * /${zkRootPath}/
  * -path(String):topic/
  * -value (metadata:String): {"topic":${topicName}, "partitionNum":${partitionNum}}
  */

object ZkPartitionOffsetHandler {

  val zkBrokerList: String = ""
  val sessionTimeout: Int = 5000
  val zkRootPath = "/spark/kafka"


  def isTopicCached(topic: String): Boolean = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    val isTopicPathExists = ZkUtil.isPathExsits(s"${zkRootPath}/${topic}")
    ZkUtil.close
    isTopicPathExists
  }

  def addRangeOffset(offsetRange: OffsetRange) = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    ZkUtil.create(s"${zkRootPath}/${offsetRange.topic}/${offsetRange.partition}",
      ZkPartitionOffsetParser.fromOffsetRange(offsetRange))
    ZkUtil.close
  }

  def deleteRangeOffset(offsetRange: OffsetRange) = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    ZkUtil.delete(s"${zkRootPath}/${offsetRange.topic}/${offsetRange.partition}")
    ZkUtil.close

  }

  def updateRangeOffset(offsetRange: OffsetRange) = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    ZkUtil.update(s"${zkRootPath}/${offsetRange.topic}/${offsetRange.partition}",
      ZkPartitionOffsetParser.fromOffsetRange(offsetRange))
    ZkUtil.close
  }

  def deleteTopicPartition(TopicPartition: TopicPartition) = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    ZkUtil.delete(s"${zkRootPath}/${TopicPartition.topic}")
    ZkUtil.close
  }

  def deleteTopicPartition(offsetRange: OffsetRange) = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    ZkUtil.delete(s"${zkRootPath}/${offsetRange.topic}")
    ZkUtil.close
  }


  def addTopicPartitionNum(topic: String, partitionNum: Int) = {
    val path = s"${zkRootPath}/${topic}"

    ZkUtil.conn(zkBrokerList, sessionTimeout)
    val jsonObj: JSONObject = new JSONObject
    jsonObj.put("topic", topic)
    jsonObj.put("partitionNum", partitionNum)
    ZkUtil.isPathExsits(path) match {
      case true => ZkUtil.update(path, jsonObj.toString)
      case false => {
        ZkUtil.create(path, jsonObj.toString)
        for (partitinId: Int <- (0, partitionNum)) {
          val rangeOffset: OffsetRange = new OffsetRange(topic, partitinId, 0, 0)
          addRangeOffset(rangeOffset)
        }

      }
    }
    ZkUtil.close
  }

  def readTopicPartitonNum(topic: String): Int = {
    val path = s"${zkRootPath}/${topic}"
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    val jSONObject = JSON.parseObject(ZkUtil.get(path), classOf[JSONObject])
    val partitionNum: Int = jSONObject.getInteger("partitionNum")
    ZkUtil.close
    partitionNum
  }


  def readRangeOffset(topic: String, partition: Int): OffsetRange = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    val offsetRange: OffsetRange =
      ZkPartitionOffsetParser.toOffsetRange(ZkUtil.get(s"${zkRootPath}/${topic}/${partition}"))
    ZkUtil.close
    offsetRange
  }

  def readRangeOffset(topicPartiton: TopicPartition): OffsetRange = {
    ZkUtil.conn(zkBrokerList, sessionTimeout)
    val offsetRange: OffsetRange =
      ZkPartitionOffsetParser.
        toOffsetRange(ZkUtil.get(s"${zkRootPath}/${topicPartiton.topic}/${topicPartiton.partition}"))
    ZkUtil.close
    offsetRange
  }
}
