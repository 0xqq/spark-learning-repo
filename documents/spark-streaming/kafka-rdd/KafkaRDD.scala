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
    
    private class KafkaRDDIterator(part:KafkaRDDPartition, context:TaskContext) extends NextIterator[R] {
     
        context.addTaskCompletionListener { context => closeIfNeeded() }

        log.info(s"Computing topic ${part.topic}, partition ${part.partition} offsets ${part.fromOffset} -> ${part.untilOffset}")

        // 在这里构建 KafkaCluster 对象实例
        val kc = new KafkaCluster(kafkaParams)
 
        // 在这里通过反射的方式构造出 kafka key 的 Decoder 实例对象
        val keyDecoder = classTag[U].runtimeClass.getConstructor(classOf[VerifiableProperties])
                               .newInstance(kc.config.props)
                               .asInstanceOf[Decoder[K]]
        // 在这里通过反射的方式构造出 kafka value 的 Decoder 实例对象
        val valueDecoder = classTag[T].runtimeClass.getConstructor(classOf[VerifiableProperties])
                                .newInstance(kc.config.props)
                                .asInstanceOf[Decoder[V]]

        // 这里调用无参方法 connectLeader 构造并返回 SimpleConsumer 对象实例
        val consumer = connectLeader 

        // 通过传入的 part:KafkaRDDPartition 对象实例来获取该分区数据块的起始 offset 数值
        var requestOffset = part.fromOffset 

        // 将消息传输迭代器置为空
        var iter:Iterator[MessageAndOffset] = null 

        // The idea is use the provided preferred host, except on task retry attempts, 
        // to minimize number of kafka metadata requests 

        //--

        // 在这里作者的想法是, 如果 task 执行失败导致 task 重试而导致 TaskContext.attemptNumber 的数值 > 0 
        // 并不是让 DAGScheduler 直接进行调度, 而是调用 KafkaCluster 中的 connectLeader 函数来进行重新连接
        // 换一个 Broker Leader 进行连接访问其 metadata 上的数值
        // 这么做的目的是减少对相同 Broker Leader 上所维护的 kafka metadata 数据的重复请求, 避免对 metadata 中未被成功计算的数据进行修改

        // 这里使用的 fold 方法刚好和 KafkaCluster.connectLeader 函数的返回值 Either[Err, KafkaCluster] 相对应
        // 1. 如果 KafkaCluster 调用 connectLeader 函数之后所返回的类型是 Err 也就是 Either.Left 这里, 那么, fold 函数中会走 errs 这里的处理逻辑
        // 2. 如果 KafkaCluster 调用 connectLeader 函数之后所返回的类型是 SimpleConsumer 也就是 Either.Right 这里, 
        //    那么, fold 函数中会走 consumer 这里, 直接将创建好的 SimpleConsoumer 对象进行返回
        private def connectLeader:SimpleConsumer = {
        	if ( context.attemptNumber > 0 ) {
        		kc.connectLeader(part.topic, part.partition).fold(
        			errs => throw new SparkException(
        				s"Couldn't connect to leader for topic ${part.topic} ${part.partition}: " 
        				+ errs.mkString("\n")),
                    consumer => consumer 
        		)
        	} else {
        		kc.connect(part.host, part.port)
        	}
        }

        private def handleFetchErr(resp:FetchResponse) {
            if (resp.hasError) {
            	// 如果 fetch 到的回复信息中 hasError 成员变量是 true, 说明发送的请求消息在被服务端接收处理的时候出现了相关的异常
            	// 在下面的 if 分支中对异常信息进行分析, 如果是因为 Kafka Broker Leader 变更而引起的异常, 则
            	// 等待配置对象中的 Kafka Broker Leader 刷新时间过后, 也就是等待这段时间之后 Kafka Server 端会选举出合适的 Kafka Broker Leader 出来
            	// 到时候重新连接一下即可
            	val err = resp.errorCode(part.topic, part.partition)
            	if ( err == ErrorMapping.LeaderNotAvailableCode || 
            		   err == ErrorMapping.NotLeaderForPartitionCode ) {
            		log.error(s"Lost leader for topic ${part.topic} partiton ${part.partition}, sleeping for ${kc.config.refreshLeaderBackoffMs}ms")
            		Thread.sleep(kc.config.refreshLeaderBackoffMs)
            	}  
            	// Let normal rdd retry sort out of reconnect attempts
            	// 对于失败重试连接之类的异常信息直接抛出, 让普通 rdd 类中的方法来解决即可
            	throw ErrorMappping.exception(err)
            }
        }

        private def fetchBatch:Iterator[MessageAndOffset] = {
        	// 在这里构建一个 FetchRequest 的请求实例对象
        	val req = new FetchRequestBuilder()
        	              .addFetch(part.topic, part.partition, requestOffset, kc.config.fetchMessageMaxBytes).build()
          
            // 调用 SimpleConsumer 类中的 fetch 函数, 将请求发送到 Kafka Server 端, 返回信息存放在 resp:FetchResponse 中
            // 其中 SimpleConsumer 中的 fetch 函数定义声明如下
            /**
               Fetch a set of messages from a topic
               @param request specifies the topic name, topic partition, starting byte offset, maximum bytes to be fetched. 
               @return a set of fetched messages 
            */
            // FetchResponse: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/FetchResponse.scala
            // 关于这个 FetchResponse 类, 主要看下其中所定义的成员变量即可
        	val resp = consumer.fetch(req)

        	// 然后, 拿着根据 kafka partition 中相关的变量信息所构建的请求发送到服务端所传回的回复消息体 resp:FetchRequest 传入到上面定义的
        	// handleFetchError 这个函数中, 而这个函数的主要作用就是, 解析 resp:FetchResponse 中携带的信息是否有异常信息, 如果有
        	// 根据异常信息选择抛出来, 还是执行重试重新连接
        	handlFetchErr(resp)
        	// kafka may return a batch that starts before the requested offset 
        	// kafka 所吐回来的 batch 中所对应的数据的 offset 有可能会比发送请求的开始 offset 数值要小, 
        	// 这种情况逐一遍历 resp:FetchRequest 实例中的 Iterator[MessageAndOffset] 将每个遍历到的
        	// MessageAndOffset 中的 offset 数值与起始 offset 数值进行比较, 如果该 MessageAndOffset 的数值 > 请求起始 offset, 
        	// 从迭代器中将这个 message drop 掉即可 
        	resp.messageSet(part.topic, part.partition)
        	    .iterator.dropWhile(_.offset < requestOffset)
         }

        // 这个重写的 close 方法来自于当前类继承的 NextIterator, close 方法会在遍历操作完成之后, 无论成功与否都会调用 1 次仅 1 次
        // 用于关闭连接,释放回收资源等操作, 在这里我们断开当前模块作为 Kafka Consumer 和 Kafka Cluster 之间的连接
         override def close() = consumer.close() 

         // getNext() 这个函数是当前类 KafkaRDDIterator 继承 NextIterator 类之后比较重要的一个方法,
         // 在 getNext() 函数中, 会负责将传入的 RDD[KafkaRDDPartition[K,V]] 类型的 RDD 转换为类型为 R 的 RDD[R]
         // 依次来实现 KafkaRDD[K,V,U,T,R] extends RDD[R] 的定义, 而具体的转换过程就定义在 getNext 这个函数中 
         override def getNext():R = {
             if (iter == null || !iter.hasNext) {
             	// 如果 iter 为空或是没有进行初始化操作
             	// 则调用 fetchBatch 方法, 从上游 Kafka Cluster 指定 topic 下的 指定 partition 的指定起始 offset 里面消费字节上限的
             	// 数据, 拉取到当前模块中, 并使用 Iterator[MessageAndOffset] 来指向这些数据, 然后再这里将这个 Iterator 赋值给 iter 
             	iter = fetchBatch 
             } 

             // 如果 iter 直线空间中 MessageAndOffset 还有后续元素没访问的话, 则走这个分支
             if (!iter.hasNext) {
                // 在这里将 errRanOutBeforeEnd 这里的提示信息贴到下面
                /**
                   Beginning offset ${part.fromOffset} is after the ending offset ${part.untilOffset} for topic ${part.topic} partition ${part.partition}
                   You either privide an invalid fromOffset, or the Kafka topic has been damaged.
                */
                // 这个地方是这样的, 如果上个请求中拉取到本地的 Iterator[MessageAndOffset] 已经遍历完了(!iter.hasNext == true), 那么判定下
                // 请求 offset 和 partition.untilOffset 是否相等, 如果相等的话就意味着预期订阅的 offset 在遍历完所有 MessageAndOffset 之后
                // 符合预期也就是不存在确实 message 没有拉取到或是遍历到, 但如果遍历完所有 MessageAndOffset 之后发现随 Message 移动的 requestOffset 
                // 没有达到预期 也就是 part.untilOffset 的数值, 则说明这个过程中有两个问题发生
                // 一个是, 起始 fromOffset 也就是这个 requestOffset 赋值的数值不对
                // 另一个是, Kafka 对应 topic 的 Broker Leader 上的 metadata 数值被损坏了
             	assert(requestOffset == part.untilOffset, errRanOutBeforeEnd(part))

                // 如果整个遍历 Iterator 成功完成, 将成功 flag 置为 true
                finished = true 

                // 因为这个分支中的 iter 已经遍历到尾了, 所以 getNext 这里返回个空即可, 但是因为返回值类型必须满足 R 类型, 所以将 null 进行一次强转
                // 强转为 R 类型
                null.asInstanceOf[R]
             } else {
             	// 如果分支到达这里, 则说明遍历还没有结束, 还有 MessageAndOffset 元素待访问
                // 在这里获取 iter 指向迭代对象的下一个元素, 即, MessageAndOffset 实例对象
                 val item = iter.next()
                 // 在这里先将 2 中情况都过滤到下面这个方法体中
                 if ( item.offset >= part.untilOffset) {
                 	// 通过断言确认下, 是否当前待处理元素的 offset 真的和 partition.untilOffset 相等, 如果相等说明这次拉取拉取多了, 会直接走到 finish 结束遍历即可
                 	// 如果是当前待处理元素的 offset >  partition.untilOffset 的话, 说明消息处理这里出了问题, 直接触发断言中函数的执行
                 	// 而这里将 errOvershortEnd 函数内部的提示信息贴到下面
                 	/**
                       Got ${itemOffset} > ending offset ${part.untilOffset} for topic ${part.topic} partition ${part.partition}
                       start ${part.fromOffset}. 
                       This should not happen, and indicates a messag may have been lost
                       预示着, 如果消息正常接收被处理的话, item.offset > part.untilOffset 的情况是不会出现的, 如果出现的话很可能消费处理消息的过程中
                       丢失了 offset 位于 [part.fromOffset, part.untilOffset) 范围内的 message 
                 	*/
                 	assert(item.offset == part.untilOffset, errOvershotEnd(item.offset, part))
                 	
                 	// 如果到达这里的话, 说明 assert 中的函数及异常信息没有执行, 而当前的 item.offset > partition.untilOffset 
                 	// 如果将 item.offset 继续拿来消费的话会引发错误, 所以这个 item 我们不对其进行消费处理, 遍历直接结束, 将其当做 !iter.hasNext() 分支处理
                 	// 也就是将 null 类型强制转换为 R 类型, 然后将其返回
                 	finished = true 
                 	null.asInstanceOf[R]
                 } else {
                 	// 如果程序到了这个分支下则说明 item.offset in [partition.fromOffset, partition.untilOffset) 区间范围内, 属于正确消费数据的情况
                 	// 在这里将 requestOffset 也就是指向下一轮消费 offset 的起始唯一的变量数值更新为 item.nextOffset 的位移
                 	requestOffset = item.nextOffset 
                 	// 然后调用开始的时候, 由用户自定义的 messageHandler 也就是处理 kafka 此次拉取的数据的函数,
                 	// 这个函数的最终目的起始也是将拉取的数据进行转换, 最终转换到 R 类型以符合 extends RDD[R] 这种类型要求
                 	requestOffset = item.nextOffset 
                 	// messageHandler [K,V] => R 
                 	messageHandler( new MessageAndMetadata(
                 		part.topic, part.partition, item.message, item.offset, keyDecoder, valueDecoder))
                 }
             }
         }
    }
 }

 private[spark]
 object KafkaRDD {
 	// 引入 KafkaCluster object 中定义的 case class  
    import KafkaCluster.LeaderOffset 

    /**
       @param kafkaParams Kafka params.
           Requires "metadata.broker.list" or "bootstrap.servers" to set with Kafka broker(s),
           NOT zookeeper serers, specified in host1:port1, host2:port form. 

       @param fromOffsets per-topic/partition Kafka offsets defining the (inclusive) starting point of the batch 
       @param untilOffsets per-topic/partition Kafka offsets defining the (exclusive) ending point of the batch 
       @param messageHandler function for translating each message into the desired type 
    */
    def apply[
        K:ClassTag,
        V:ClassTag,
        U <: Decoder[_]: ClassTag,
        T <: Decoder[_]: ClassTag,
        R:ClassTag](

        sc:SparkContext, 
        kafkaParams:Map[String, String],
        fromOffsets:Map[TopicAndPartition, Long],
        untilOffsets:Map[TopicAndPartition, LeaderOffset],
        messageHandler:MessageAndMetadata[K,V] => R
       ):KafkaRDD[K,V,U,T,R] = {
       

    }

 }