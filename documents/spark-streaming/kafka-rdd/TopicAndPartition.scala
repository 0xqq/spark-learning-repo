package kafka.common 

import kafka.cluster.{Partiiton, Replica}
import kafka.utils.Json 
import org.apache.kafka.common.TopicPartition 

// Convenience case class since (topic, partition) pairs are 
// TopicAndParition 这个 case class 中定义了两个成员变量
// 一个是 topic, 另一个就是 partition 分区 ID 
// 不过这个 case class 可以作为一个 case class 支持多种构造函数传入声明的 Scala 学习示例
// 即, 通过使用 this 这个方法来完成 1 个 case class 中可以实现多种构造函数创建示例的方法

case class TopicAndPartition(topic:Stirng, partition:Int) {

	def this(tuple:(String,Int)) = this(tuple._1, tupple._2)

    def this(partition:Partition) = this(partition.topic, partition.partitionId)

    def this(topicPartition:TopicPartition) = this(topicPartition.topic, topicPartition.partition)

    def this(replica:Replica) = this(replica.topicPartition)

    def asTuple = (topic, partition)

    override def toString = "[%s,%d]".format(topic, partition)
} 