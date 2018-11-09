package org.apache.spark.streaming.kafka 

import org.apache.spark.Partition 

/**
 @param topic kafka topic name 
 @param partition kafka partiton id
 @param fromOffset inclusive starting offset 
 @param untilOffset exclusive ending offset 
 @param host preferred kafka host, i.e. the leader at the time the rdd was created
 @param port prefereed kafka host's port
*/

// 在这里我们继承 spark 原生的 Partition 类, 并在基类的基础上定义了 kafka 相关的成员变量
private[spark] 
class KafkaRDDPartition (
	val index:Int, 
	val topic:Stirng, 
	val partition:Int, 
	val fromOffset:Long,
	val untilOffset:Long,
	val host:String, 
	val port:Int
	) extends Partition 


private[spark]
object KafkaRDDPartition {
    // 在与类同名的 object 中所定义的 apply 函数中给出了 KafkaRDDPartition 类创建 && 实例化的全部逻辑 
    // 通常在创建 apply 函数中可以在创建对象之前可以对传入的参数进行预处理, 
    // 让处理后的数据项和类构造函数的传入参数相匹配后再创建对象实例
	def apply(
        index:Int,
        topic:String,
        partition:Int, 
        fromOffset:Long, 
        untilOffset:Long,
        host:String,
        port:Int
	):KafkaRDDPartition = new KafkaRDDPartition (
    index, topic, partition, fromOffset, untilOffset, host, port)
}