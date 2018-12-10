// References: https://github.com/koeninger/spark-1/blob/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka/OffsetRange.scala

package org.apache.spark.streaming.kafka 

import kafka.common.TopicAndPartition 

// Something that has a collection of OffsetRanges 
//接下来定义的这个对象 HasOffsetRanges 它是由许多个 OffsetRange 对象所构成的集合
trait HasOffsetRanges {
	def offsetRanges:Array[OffsetRange]
}

// Represents a range of offsets from a single Kafka TopicAndPartition 
// OffsetRange 它的作用对象是 1 个 Kafka 集群中的 1 个 Topic 下的 1 个分区/Partition 而言的
// TopicAndPartition 定义的就是 1 个 Topic 下的 1 个分区 Partition 但是仅仅是定义, 并没有记录这个分区下的数据记录情况, 比如当前分区还有多少条数据? 
// 被订阅方消费了多少条(不过消费的消息条数通常放到消费订阅方自己来维护比较合理) 
// 订阅方是多个且是主动从 kafka 这里拉取数据, 所以 offset 消费的进度是由下游订阅者这里来维护比较稳妥, 这样也不会给 kafka server 带来数据维护和提供访问的压力.

// 而, 是的, 没错, 这个所谓的 '被订阅放消费了多少条的消费 offset 进度, 以及下次消费的起始 offset 数值', 
// 并且是由数据订阅一方所维护的这个对象便是由下面这个 OffsetRange 这个类来进行定义的.

// 所以, 在阅读代码之前, 大概了解了它所扮演的角色后, 大概能推断出这个类中最基本能包含的成员变量它们便是: 
// kafka 的 topic 及 topic 中的 partitionId 数值, 这两个数值唯一指定了 kafka cluster 中某 1 个 topic 下其中的 1 个分区, 
// 以及, 记录这个分区所消费的数据的 [起始位移,终止位移) ==> [startOffset, untilOffset)

final class OffsetRange private (
	// kafka topic name 
	// kafka topic 的名字
	val topic:String,

	// kafka partition id 
	// kafka 分区的 id 号
    val partition: Int,

    // inclusive starting offset 
    // 消费起始的 offset/位移, 并且是闭区间
    val fromOffset:Long, 

    // exclusive ending offset  
    // 消费结束的, offset/位移, 并且是开区间
    val untilOffset:Long) extends Serializable {

    import OffsetRange.OffsetRangeTuple 

    // this is avoid to ClassNotFoundException during checkpoint restore 
    // 在此处之所以增加了 toTuple 的方法定义目的是为了防止 streaming 的 checkpoint 进行数据恢复时, 因找不到类而抛出 ClassNotFoundException 这种异常
    private[streaming]
    def toTuple:OffsetRangeTuple = (topic, partition, fromOffset, untilOffset)

    object OffsetRange {
        
        // 在 object 中定义 create 静态方法, 内部实际上调用的是 OffsetRange 类的构造函数
        def create(topic:String, partition:Int, fromOffset:Long, untilOffset:Long):OffsetRange = {
        	new OffsetRange(topic, partition, fromOffset, untilOffset)
        }


        // 支持传入不同参数的 create 静态方法, 传入参数是包含了 kafka 中特定 topic 下特定 partition 的 TopicAndPartition 对象实例
        def create(
        	topicAndPartition:TopicAndPartition, 
        	fromOffset:Long,
        	untilOffset:Long):OffsetRange = 
            new OffsetRange(topicAndPartition.topic, topicAndPartition, fromOffset, endOffset)

        // apply 函数的作用便是, 在对象被创建之初, 即其对应参数的构造函数被调用完毕之后
        // 会立即调用同参的定义在 object 中的 apply 参数, 来为刚刚创建好的对象执行各个成员变量的初始化逻辑步骤
        def apply(topic:String, partition:Int, fromOffset:Long, untilOffset:Long):OffsetRange = {
        	new OffsetRange(topic, partition, fromOffset, untilOffset)
        }

        def apply(
        	topicAndPartition:TopicAndPartition, 
        	fromOffset:Long,
        	endOffset:Long) = {
        	new OffsetRange(topicAndPartition.topic, topicAndPartition.partition, fromOffset, untilOffset)
        }

        // this is to avoid ClassNotFoundException during checkpoint restore 
        private[spark]
        type OffsetRangeTuple = (String, Int, Long, Long)

        
        private[streaming]
        def apply(t:OffsetRangeTuple) = 
            new OffsetRange(t._1, t._2, t._3, t._4)
    }

}
