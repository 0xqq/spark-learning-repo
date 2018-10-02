package com.spark.aimer.exactlyonce.streaming

import com.spark.aimer.exactlyonce.zk.{ZkPartitionOffsetHandler, ZkPartitionOffsetParser}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.streaming.{Milliseconds, Seconds, StreamingContext}
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.{SparkConf, SparkContext, TaskContext}
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.{HasOffsetRanges, KafkaUtils, OffsetRange}
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent

/**
  * Created by Aimer1027 on 2018/10/1.
  */
object ExactlyKafkaSparkStreamingApp {

  val zkHandler = ZkPartitionOffsetHandler

  /**
    * User's implements this method as service logic
    *
    **/
  def serviceLogicOp(stream: DStream[String]) = {
    // here we do service level operations
    // 1. calculate the step by step either by SQLContext sqls or RDD directly transforms && actions
    // 2. save/sync results to external storages like redis/kafka/hdfs/hbase/es by different apis
  }


  /**
    * load OffsetRange's from external storage
    * here we use the zookeeper as the ''external storage''
    *
    * @param topicPartitionNumMap in this map key name of the topic , value is the partition number that how many
    *                             partitions in total under this topic in kafka cluster
    * @return Map[TopicPartition,Long] in this map , TopicPartition can contain the info which topic ,
    *         and which partition and the Long can contain the partition's offset value = untilOffset - fromOffset
    **/
  def loadRangeOffsetFromExternal(topicPartitionNumMap: Map[String, Int]): Map[TopicPartition, Long] = {
    var topicPartition = Map[TopicPartition, Long]()
    for (topicName: String <- topicPartitionNumMap.keySet) {
      val partitionNum: Int = topicPartitionNumMap.get(topicName).get
      if (zkHandler.isTopicCached(topicName)) {
        zkHandler.addTopicPartitionNum(topicName, partitionNum)
      }
      val offsetRange: OffsetRange = zkHandler.readRangeOffset(topicName, partitionNum)
      topicPartition += (offsetRange.topicPartition() -> offsetRange.count())
    }
    topicPartition
  }

  /**
    * save the offsetRange back to external storage
    * here we use the zookeeper as our ''external storage''
    *
    * @param offsetRange in which contains the
    *                    1. topic name : String
    *                    2. partition id : Int
    *                    3. fromOffset value :Long
    *                    4. untilOffset value :Long
    *                    5. count: untilOffset - fromOffset
    **/
  def syncRangeOffsetToExternalStorage(offsetRange: OffsetRange) = {
    zkHandler.addRangeOffset(offsetRange)
  }

  /**
    * In this method we create direct stream between spark and kafka
    * and we also use the offsets storaged in ''external storage'' to init the stream
    *
    * @param ssc            StreamingContext
    * @param topicPartition key:topic name , value: how many partitions in total in this topic
    * @return DStream[String] the data stream from kafka to Spark StreamingContext
    **/
  def kafkaDStreamInit(ssc: StreamingContext, topicPartition: Map[String, Int], loadCache: Boolean): DStream[String] = {

    val brokers = ""
    val group = "aimer-kafka-streaming-exactly-once"
    val topics = topicPartition.keySet
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> group,
      "auto.offset.reset" -> "latest",
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    val stream: DStream[String] = loadCache match {
      case true =>
        KafkaUtils.createDirectStream[String, String](
        ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams, loadRangeOffsetFromExternal(topicPartition))
      ).map(_.value())

      case false =>
        KafkaUtils.createDirectStream[String, String](
          ssc, PreferConsistent, Subscribe[String, String](topics, kafkaParams)
        ).map(_.value())
    }
    stream
  }


  /**
    * In this method we commit the consumed offset info back to ''external storage''
    * we traverse each rdd's each partition's OffsetRange
    * and write its consumed detail info back to ''external storage''
    *
    * @param stream DStream[String] the data streaming from kafka to Spark StreamingContext
    **/
  def kafkaDStreamCommit(stream: DStream[String]) = {
    stream.foreachRDD { eachRDD =>

      // first , we get the array of OffsetRange by calling eachRDD's
      val offsetRangeArray: Array[OffsetRange] = eachRDD.asInstanceOf[HasOffsetRanges].offsetRanges

      eachRDD.foreachPartition { eachRDDPartitionIter =>
        val partitionId = TaskContext.getPartitionId
        val offsetRangeByPartition: OffsetRange = offsetRangeArray(partitionId)
        syncRangeOffsetToExternalStorage(offsetRangeByPartition)
      }
    }
  }

  def main(args: Array[String]) = {

    val batchInterval: Int = 10

    val conf = new SparkConf().setAppName("KafkaSparkStreamingExactlyOnceApp")

    // here we set the task only fail once , in case of stage and job retry
    // to produce duplicated messages
    conf.set("spark.task.maxFailures", "1")

    val sc = new SparkContext(conf)

    val ssc = new StreamingContext(sc, Seconds(batchInterval))

    ssc.remember(Seconds(batchInterval * 2))

    val topicPartitonNum: Map[String, Int] = Map()

    val streaming: DStream[String] = kafkaDStreamInit(ssc, topicPartitonNum)

    serviceLogicOp(streaming)

    kafkaDStreamCommit(streaming)

    ssc.stop(false, true)
  }
}
