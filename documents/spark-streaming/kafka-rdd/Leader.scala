package org.apache.spark.streaming.kafka 

import kafka.common.TopicAndPartition 

// References: https://github.com/koeninger/spark-1/blob/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka/Leader.scala


// Host info for the leader of Kafka TopicAndPartition 
// Kafka TopicAndPartition 对应的所有 kafka 节点中, 位于 LSR 集合中的作为 Leader 节点的数据源信息
// 定义了一个 final 类
final class Leaader private (
    // kakfa topic name 
    // kafka topic 的名字
    val topic:String, 

    // kafka partition id 
    // kafka 分区 id 数值
    val partition:Int, 

    // kafka hostname 
    // leader 节点所在机器的 hostname 
    val host:String, 

    // kafka host's port 
    // kafka 对外提供的 host 的端口号
    val port:Int
	) extends Serializable 

// 在这里定义了 Leader 的 object 声明, 在其中定义了相关的方法
object Leader {
	// 在 Leader object 中定义的 create 方法中传参并调用 Leader 的构造函数
    def create(topic:String, partition:Int, host:Sring, port:Int):Leader = 
        new Leader(topic, partition, host, port)

    def create(topicAndPartition:TopicAndPartition, host:String, port:Int):Leader = 
        new Leader(topicAndPartition.topic, topicAndPartition.partition, host, port)


    def apply(topic:String, partition:Int, host:String, port:Int):Leader = 
        new Leader(topic, partition, host, port)

    // scala object 中的 apply 方法类似于 java 静态方法中的初始化方法
    // 我们在初始化调用的时候, 有了 apply 方法之后可以避免使用 new 方法
    // 同时在对应的对象被创建对象之初, 会首先调用 object 中的 apply 方法, 进行类中对象的初始化步骤
    def apply(topicAndPartition:TopicAndPartition, host:String, port:Int):Leader = 
        new Leader(topicAndPartition.topic, topicAndPartition.partition, host, port)
}

























