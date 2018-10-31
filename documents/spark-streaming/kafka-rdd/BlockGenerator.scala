// Reference: https://github.com/apache/spark/blob/5264164a67df498b73facae207eda12ee133be7d/streaming/src/main/scala/org/apache/spark/streaming/receiver/BlockGenerator.scala

package org.apache.spark.streaming.receiver 

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable.ArrayBuffer 
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.internal.Logging 
import org.apache.spark.storage.StreamBLockId 
import org.apache.spark.


























// 在阅读完作者自己实现的加持各种可靠, 无丢失 buff 的 ReliableKafkaReceiver.scala 之后, 发现这个类中很多方法
// 都是各种处理 Block 和 Seq[topic.partition-offset] 二者的关联映射关系, 
// 我在这里其实并不是很清楚为什么每次在构建映射关系的时候,为何要将缓存 key:topic.parition, value:offset 关系的 hash map 给清空, 
// 也就是下面注释的这段代码中最后的 clear 方法调用清空 hash map 
/**

// Remember the current offset for each topic and partition. 
// This is called when a block is generated .
private def rememberBlockOffsets(blockId:StreamBlockId):Unit = {
	// Get a snapshot of current offset map and store with related block id. 
	val offsetSnapshot = topicPartitionOffsetMap.toMap 
	blockOffsetMap.put(blockId, offsetSnapshot)
	topicPartitionOffsetMap.clear() // why clean the contents in hash map here ? 
}
*/
// 虽然我觉得是, 上游 Receiver 基类在构建 block 的时, 是按照整个系统中的 batch 构建周期, 周期性地读取数据并构建,
// 每次存放到 hash map 中的 topic,partition - offset 仅仅是一个 batch 时间周期的数据信息, 因为这个 batch 完成, 数据入 block, 
// 建立这个 block 的 block id 与 多个 topic.partition 的 offset 之后, 清空 topic.partition offset 的 hash map 缓存, 
// 好等待下个 batch 新数据到来好记录新 batch 中从 kafka 拉取的 topic.partition - offset 的数据关系对 ? 
// 后来想了一下, 是因为我对调用 rememberBlockOffsets 函数的 BlockGeneratorListener 中定义的回调函数: onGenerateBlock 
// 这个回调函数所处理的 Event 到达的时机不理解, 导致我不清楚 rememberBlockOffsets 函数被执行的时间背景是什么样的, 
// 进而导致了我对其中缓存 hash map 中数据 clear 清空方法的不了解, 所以这里决定阅读批注一下 BlockGenerator.scala 这一份源码

