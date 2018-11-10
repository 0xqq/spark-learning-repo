package org.apache.spark.streaming.kafka 

import scala.reflect.{classTag, ClassTag}
import org.apache.spark.{Logging, Partition, SparkContext, SparkException, TaskContext}
import org.apache.spark.rdd.RDD 
import org.apache.spark.util.NextIterator 
import java.util.Properties 
import kafka.api.{FectchRequestBuilder, FetchResponse}
import kafka.common.{ErrorMapping, TopicAndPartition}
import kafka.consumer.{ConsumerConfig, SimpleConsumer}
import kafka.message.{MessageAndMetadata, MessageAndOffset}
import kafka.serializer.Decoder 
import kafka.utils.VerifiableProperties 

/**
 A batch-oriented interface for consuming from Kafka.
 Starting and ending offsets are specified in advance,
 so that you can control exactly-once semantics. 

 @param kafkaParams Kafka configuration parameters 
 @param batch Each KafkaRDDPartition in the batch corresponds to a range of offsets for a given kafka topic/partition
 @param messageHandler function for translating each message into the desired type
*/

/**
 KafkaRDD 是以批处理的方式从 Kafka 中消费数据的接口,
 KafkaRDD 从 Kafka 端每次消费的数据的起止[startOff, endOffset) 在拉取数据之前都是固定好的, 
 通过确保每次消费数据的起止 offset 已知这种可控的方式来保证一次性消费的语义. 

 @param kafkaParams 这个参数中传入的是 kafka 数据消费端的配置项信息
 @param batch 我们将从 kafka 端所消费的起止 offset 已知的数据封装成各个 KafkaRDDPartition 对象实例, 
        从每个 KafkaRDDPartition 中可以当前所获取读取 kafka 数据是来自于哪个 topic 的哪个 partition
 @param messageHandler 这个是将从 kafka 端接收到的消息体转换为需要类型的操作函数
*/

private[spark]
class KafkaRDD[
    K:ClassTag, V:ClassTag, U <: Decoder[_]:ClassTag, V <:Decoder[_]:ClassTag, R:ClassTag] private[spark](
    sc:SparkContext, kafkaParams:Map[String, String], 
    val offsetRanges:Array[OffsetRange],
    leaders:Map[TopicAndPartition, (String, Int)],
    messageHandler:MessageAndMetadata[K,V] => R  ) extends RDD[R](sc, Nil) with Logging with HashOffsetRanges {
    // 这里的 messageHandler 所传入的是定义了参数类型和返回值的函数

    // 这里 override 的函数是定义在 RDD 基类中的
    // RDD 基类中的方法注释信息如下
    /**
     Implemented by subclasses to return the set of partitions in this (RDD).
     This method will only be called once, so it is safe to implement a time-consuming computation in it. 
     The partitions in this array must satisfy the following property:
     `rdd.partitions.zipWithIndex.forall { case (partition, index) => partition.index == index }`

     (getPartitions) 在子类中给出这个函数的实现逻辑, 函数的返回值是当前 RDD 中的数据进行分区.
     getPartitions 函数仅会被调用一次, 所以即便是在子类中执行的时间开销大的计算逻辑都是 ok 的.
     并且返回值的 Array[Partition] 数组必须满足如下的属性才可以:
     `rdd.partitions.zipWitthIndex.forall { case (partition, index) => partition.index == index}`
      (不过这里的所指的必须满足这种属性,这里还不太明白注释说的是什么意思)
    */
    override def getPartitions:Array[Partition] = {
    	/**
          zipWithIndex 这个 API 会将输入的元组增加一个 index, 例如下面的这个 case (o, i)
          o 对应的是 map 函数传入的 offsetRanges:Array[OffsetRange] 的 1 个元素 OffsetRange 
          i 对应的是当前遍历的是 offsetRanges:Array[OffsetRange] 是当前遍历的第几个元素
    	*/
        offsetRanges.zipWithIndex.map { case (o,i) =>
            val (host, port) = leaders(TopicAndPartition(o.topic, o.partition))
            // 此处可参考 KafkaRDDPartition 类: 
            // https://github.com/koeninger/spark-1/blob/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka/KafkaRDDPartition.scala
            // class KafkaRDDPartiton (val index:Int, val topic:String, val partition:Int, 
            // val fromOffset:Long, val untilOffset:Long, val host:String, val port:Int) extends Partition 
            // 所以在这里我们可以看出, 之所以使用 zipWithIndex 来访问 offsetRanges:Array[OffsetRange] 目的是为每个 Array 中的元素对象根据访问顺序生成 index 
            new KafkaRDDPartition(i, o.topic, o.partition, o.fromOffset, o.untilOffset, host,port)
        }.toArray 
    }  

    override def getPreferredLocations(thePart:Paritition):Seq[String] = {
    	val part = thePart.asInstanceOf[KafkaRDDPartition]
    	// TODO is additional hostname resolution necessary here 
    	// 在这里代码 committer 不确定在这里将 partition 所在的 hostname 作为 KafkaRDDPartition 存放的首选机器名称进行返回这种处理是否有必要
    	Seq(part.host)
    }


    // 下面是各种提示错误异常信息
    private def errBeginAfterEnd(part:KafkaRDDPartition):String = 
        s"Beginning offset ${part.fromOffset} is after the ending offset ${part.untilOffset}"
            + s"for topic ${part.topic} partition ${part.partition}." 
            + "You either provided an invalid fromOffset, or the Kafka topic has been damaged"

    private def errRanOutBeforeEnd(part:KafkaRDDPartition):String = 
        s"Ran out of messages before reaching ending offset ${part.untilOffset} " 
        + "for topic ${part.topic} partition ${part.partition} start ${part.fromOffset}." 
        + "This should not happen, and indicates that messages may have been lost"


    private def errOvershotEnd(itemOffset:Long, part:KafkaRDDPartition):String = 
        s"Got ${itemOffset} > ending offset ${part.untilOffset} "
        + "for topic ${part.topic} partiton ${part.partition} start ${part.fromOffset}."
        + " This should not happen, and indicates a message may have been skipped"


    // 这里 override 的函数 compute 是定义在 RDD 类中的函数, 在 RDD 类中给出这个类/接口的描述如下
    /**
      :: DeveloperApi ::
      Implemented by subclasses to compute a given partiton 
      这个 compute 函数由开发者在继承 RDD 的子类中给出计算逻辑, 
      //-----
      其实在这个地方我挺好奇其他 RDD 的子类是如何实现这个 compute 方法的, 以及这个 compute 方法在何处调用(看过代码之前想的)
      这个 compute 函数最大的功能便是, 将 RDD 中的一个分区, 也就是以 Partition 为最小的数据存储单位, 将这个 Partition 中的数据
      分配给 TaskContext 进行调度和计算, 这个 TaskContext 也就是我们常说的 Spark 计算中的 Task 的上下文, 
      而, 是的没错, 我们常常说的 1 个 Task 对应 1 个 Partition 的计算, 便是在 compute 这个函数中实现的, 
      而所有的 Task 就是调度器这个线程池中的任务队列排队的元素, 而这个线程池的并发度取决于计算机中给这个线程池分配的核数
      并发度和线程池队列容量其实关系并不是很大, 大多数情况下 线程池队列容量 <= 并发度. 

      类似的就像是把一个数据块交给一个线程进行处理的感觉
      //-----
      还有就是这个 TaskContext 这里的代码也打算看看. 
      //---- 
      只需要根据传入的参数
      1. thePart:Partition  2. context:TaskContext
      进行计算得到返回值的类型满足
      Iterator[R], 注意这里的 R 类型和 extend 的 RDD[T] 的泛型类型保持一致
    */ 
    override def compute(thePart:Partition, context:TaskContext):Iterator[R] = {
    	// 在这里首先将 Partition 调用 asInstanceOf 转换为 KafkaRDDPartition 
    	// 因为 KafkaRDDPartition 是 Partition 的子类, 所以在这里通过强制类型转换, 来进行向下塑型
    	val part = thePart.asInstanceOf[KafkaRDDPartiton]

    	// 在计算开始之前, 先判断下当前 KafkaRDDPartition 中的开始 offset 必须 <= 结束 offset 
    	assert(part.fromOffset <= part.untilOffset, errBeginAfterEnd(part))

    	// 如果起始 = 结束 offset, 则不进行任何处理, 因为这个分区中没有任何来自于上游的数据,直接返回 Iterator.empty 
    	if (part.fromOffset == part.untilOffset) {
    		log.warn("Beginning offset ${part.fromOffset} is the same as ending offset " 
    			+ s"skipping ${part.topic} ${part.partition}")
    		Iterator.empty 
    	} else {
    		// 否则的话, 将这个分区中的数据交付给 TaskContext, TaskContext 在被分配这个分区数据之后, 应该会被丢到队列中排队等待计算资源调度到它, 然后进行计算
    		// 而所计算的就是这个 part 中的数据
    		new KafkaRDDPartition(part, context)
    	}
    }

    // NextIterator 代码注释加到另一个文件中, KafkaRDDIterator 如类名, 是一个专为 KafkaRDDPartition 类型的 RDD 而特化的迭代器
    // TaskContext 这个类十分重要, 将代码注释写另一个文件中
    // 
    private class KafkaRDDIterator(part:KafkaRDDPartition, context:TaskContext) extends NextIterator[R] {
     
        context.addTaskCompletionListener { context => closeIfNeeded() }

        log.info(s"Computing topic ${part.topic}, partition ${part.partition} offsets ${part.fromOffset} -> ${part.untilOffset}")

        // TaskContext go first --> TaskContext 
        val kc = new KafkaCluster(kafkaParams)






    }




 }



