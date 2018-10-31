package org.apache.spark.streaming.kafka 

import java.util.Properties 

import scala.collection.Map 
import scala.refelct.{classTag, ClassTag}
import kafka.consumer.{KafkaStream, Consumer, ConsumerConfig, ConsumerConnector}
import kafka.serializer.Decoder 
import kafka.utils.VerifiableProperties 

import org.apache.spark.Logging 
import org.apache.spark.storage.StorageLevel 
import org.apache.spark.streaming.StreamingContext 
import org.apache.spark.streaming.dstream._
import org.apache.spark.streaming.receiver.Receiver 
import org.apache.spark.util.Utils 

// Input stream that pulls messages from a Kafka Broker.
// KafkaInputDStream 对象抽象定义了从 Kafka Broker 拉取数据后而构建的 InputDStream 这个过程中调用的方法和执行的逻辑

// @param kafkaParams Map of kafka configuration parameters.
// @param kafkaParams 这个参数中存放的是 kafka 定制化的配置信息

// @param topics Map of (topic_name -> numPartitions) to consume. Each partiton is consumed in its own thread. 
// @param topics 中记录了 topic_name 即该 topic 下总共有多少个分区的 key,value 映射对, 并且每个分区会有一个单独的线程来进行消费拉取数据

// @param storageLevel RDD storage level. 
// @param storageLevel 这个参数指定了 RDD 进行数据存储的层级, 有全内存的, 有内存_磁盘的, 也有磁盘的, 也就是 RDD.persist() 进行中间结果缓存的时候
// 所设定的数据缓存的层级

private[streaming]
class KafkaInputDStream [
    K:ClassTag, // 传入 key 的类类型
    V:ClassTag, // 传入 value 的类类型
    U <: Decoder[_]:ClassTag, // 传入实现了/继承了 Decoder[T] 的类的类型, U 将作为 key 的解码器, 
    T <: Decoder[_]:ClassTag] ( // 传入实现/继承了 Decoder[T] 的类的类型, T 将作为 value 的解码器, 通常用在从网络中接收到编码数据并对其进行解码的时候
    @transient ssc_ : StreamingContext,  // 序列化的时候我们跳过 ssc_:StreamingContext 对象的话, 就会在这个传入参数上加个 @transient 
    kafkaParams:Map[String, String], /
    topics:Map[String,Int],
    useReliableReceiver:Boolean, 
    // 这里通过布尔值提供了一个是否使用 reliable kafka receiver 的选项, reliable kafka receiver 是开发者在原有的 KafkaReceiver 的逻辑上增加了些相应的升级策略
    // 因为先看的 KafkaInputDStream 这份代码, 暂时还没有看 KafkaReceiver 和 ReliableKafkaReceiver 这里的代码逻辑, 所以不好下结论
    // 目前可知的便是 ReliableKafkaReceiver = KafkaReceiver 加持了些可靠的逻辑和策略 (即, 高配版 KafkaReceiver)
    storageLevel:StorageLevel
    ) extends ReceiverInputDStream[(K,V)](ssc_) with Logging {
    	// KafkaInputDStream 继承自 ReceiverInputDStream 这个类, 针对上游数据源是 kafka 做了一些精细化特定于 kafka 数据流特点的功能细化

    	def getReceiver():Receiver[(K,V)]  = {
    		if (!useReliableReceiver) {
    			new KafkaReceiver[K, V, U, T](kafkaParams, topics, storageLevel)
    		} else {
    			new ReliableKafkaReceiver[K, V, U, T](kafkaParams, topics, storageLevel)
    		}
    	}
    }

    private[streaming]
    class KafkaReceiver[
        K:ClassTag,
        V:ClassTag,
        U <: Decoder[_]:ClassTag,
        T <: Decoder[_]:ClassTag](
            kafkaParams:Map[String, String],
            topics:Map[String, Int],
            storageLevel:StorageLevel
       ) extends Receiver[(K,V)](storageLevel) with Logging {

       // Connect to Kafka 
       // 这个是 Spark 中作为数据订阅消费者的一方与 Kafka 集群建立连接时候使用到的实例对象
       var consumerconnector: ConsumerConnector = null 

       // spark 与 kafka 二者之间断开连接的时候, 进行连接释放资源回收等等功能都放到这个 stop 方法中
       def onStop() {
           if (consumerConnector != null) {
           	    consumerConnector.shutdown() 
           	    consumerConnector = null 
           }
       }

       // spark 与 kafka 二者建立连接执行逻辑, 其中 kafka 作为上游数据流提供方, spark 作为数据订阅方/consumer 
       def onStart() {
       	   // 在这里打印 INFO 级别的日志, 通过提取出传递到 spark 中的 kafka 配置可得知, spark 作为 consumer 所在的是哪个 group 
           logInfo("Starting Kafka Consumer Stream with group: " + kafkaParams("group.id"))

           // Kafka connection properties 
           // spark 作为 consumer 而言, 在 Consumer 初始化的时候需要构建 ConsumerConfig 这个实例, 而这个实例构建的时候需要传递
           // 配置 kafka 参数的 Properties 的实例对象才行, 所以在这里我们创建一个空的 Properties 对象实例, 
           // 然后将通过参数 Map[String,String] 类型的 kafkaParams 中的配置项逐一传递给空的 Properties 对象, 也就是下面的 kafkaParams.foreach 这里的作用
           val props = new Properties()

           // 将传递进来的 kafkaParams 中的 key value 对逐一传递给 Properties 
           // 其实, Properties 本质上也是一个 hash map  
           kafkaParams.foreach( param => props.put(param._1, param._2))

           // 注意一下背景, 这个 branch 的代码提交的时候 kafka 的版本还是 0.8.x 所以此时的 offset 还存放在 zk 端
           // 所以在 kafkaParams 中设定 zookeeper.connect 这个参数配置项是很正常的
           val zkConnect = kafkaParams("zookeeper.connect")

           // Create the connection to the cluster 
           // 接下来我们开始构建 Consumer 初始化必备的实例对象 ConsumerConfig 它需要 zookeeper 的地址, 好建立到 zk 的连接
           logInfo("Connecting to Zookeeper: " + zkConnect)
           // 构建 ConsumerConfig 实例对象
           val consumerConfig = new ConsumerConfig(props)

           // 通过对前文对 OffsetRange 类的代码阅读, 我们可以知道 create 的方法会返回一个创建好的类的实例对象, 
           // 并且这个方法是定义在与 class 同名的 object 中的,  create 中通常会 new 一个实例对象, 
           // 然后再对其类中的各个成员变量根据传入其中的参数进行初始化
           // --- 
           // 调用 Consumer object 中的 create 方法, 通过传入初始化好的 ConsumerConfig 实例对象
           // 来构建 ConsumerConnector 实例对象, 此时 spark 也将作为 group.id 对应 consumer group 中的一员来
           // 订阅消费 kafka 作为上游数据提供方的数据
           consumerConnector = Consumer.create(consumerConfig)
           // 好的 consumer 通过传入参数构建完毕, 我们打个日志标识下
           logInfo("Connected to " + zkConnect)


           // 在这里, 我们通过反射的方式来实例化 U 泛型的 Decoder 实例对象, 传入 VerifiableProperties 这个类来进行参数的校验
           // 校验没有通过的话会抛出异常, 通过的话，我们就用 ConsumerConfig 实例中的 Properties 成员变量存放的配置信息来初始化
           // key 类型的解码器/Decoder 因为来自 kafka 数据流中的数据会存在序列化, 所以在这里我们的 spark 作为数据消费方/consumer 会为其构建解码器/Decoder
           // 用来将编码后的数据进行解码, 以及最终构建的解码器的解码类型
           // 和开始传入的 Key Value 的泛型类型是相对应的

           val keyDecoder = classTag[U].runtimeClass.getConstructor(classOf[VerifiableProperties])
                            .newInstance(consumerConfig.props)
                            .asInstanceOf[Decoder[K]]

           val valueDecoder = classTag[T].runtimeClass.getConstructor(classOf[VerifiableProperties])
                            .newInstance(consumerConfig.props)
                            .asInstanceOf[Decoder[V]]

           // Create threads for each topic/message Stream we are listening 
           // 为我们监听的每个 topic 和 消息创建线程来处理它们, 
           // 为了更加直观一些, 我将 kafka010 版本中的 ConsumerConnector 中的 createMessageStream 方法签名拷贝过来了
           /**
            *  Create a list of MessageStreams for each topic.
            *
            *  @param topicCountMap  a map of (topic, #streams) pair
            *  @param keyDecoder Decoder to decode the key portion of the message
            *  @param valueDecoder Decoder to decode the value portion of the message
            *  @return a map of (topic, list of  KafkaStream) pairs.
            *          The number of items in the list is #streams. Each stream supports
            *          an iterator over message/metadata pairs.
            *  def createMessageStreams[K,V](topicCountMap: Map[String,Int],
            *            keyDecoder: Decoder[K],
            *            valueDecoder: Decoder[V]) : Map[String,List[KafkaStream[K,V]]]
            *  从方法签名这里可以看出, 返回的 Map 中 key 是 topic , value 则是由 KafkaStream 所构成的 list 
            *  这个地方虽然调用的是 consumerConnector 这里的 createMessages 实际上调用的是
            *  ZookeeperConsumerConnector 这个类中定义的相关方法, 为了更好的了解 kafka 和 spark 底层通信涉及到的细节
            *  我在同一个文档下把 ZookeeperConsumerConnector.scala 这个文件加了进来, 
            *  不过需要注意的是, 在实际的项目结构中它们并不是在同一个文件夹下, 甚至不再同一个 package 中
            */             
            val topicMessagesStreams = consumerConnector.createMessages(
            	topics, keyDecoder, valueDecoder)

            // 在这个地方通过调用 Utils 类中的方法来构建一个线程池, 线程池的名字叫做 'KafkaMessageHandler'
            // 而线程池的大小与 topics hash map 中的 topic 个数相同, 也就是线程池中总共会维护和 topic 数目相同的线程的个数
            // 同时从命名方式 FixedThreadPool 得知, 该线程池的类型是线程池中线程个数是固定的类型
            val executorPool = Utils.newDaemonFixedThreadPool(topics.values.sum, "KafkaMessageHandler")


            try {
            	// Start the messages handler for each partition 
            	// 接下来开始访问结构类型为  Map[String, List[KafkaStream[K,V]]] 中所有的 List[KafkaStream[K,V]] 
            	// KafkaStream[K,V] 对象实例, 下面代码中的 values.foreach 是逐个 traverse List[KafkaStream[K,V]]
            	// 而 foreach 中所嵌套的 foreach 是逐一访问 List[KafakaStream[K,V]] 中的 KafkaStream[K,V] 实例
            	topicMessagesStreams.values.foreach { streams =>
            	    // 这里 foreach 的对象是 List[KafkaStream[K,V]] 
                    streams.foreach { 
                    	// 这里 foreach 的对象是 KafkaStream[K,V]
                    	// 所以这里调用逻辑是, 创建一个 MessageHander 实例, 传入参数是 KafkaStream[K,V]
                    	// 而这里的线程池调用 submit 的方法实则是将继承了线程调用接口的 MessageHandler 对象实例
                    	// 丢到我们刚刚创建线程池中的队列中等待空闲线程的执行 MessageHandler 类实例中实现接口中定义的方法
                    	stream => executorPool.submit(new MessageHandler(stream))
                    }
            	}
            } finally {
            	executorPool.shutdown()
            	// Just causes threads to terminate after work is done 
            	// 在结尾通过调用 shutdown 方法是为了避免执行任务结束线程未退出这种情况发生
            }
       }

    // Handles Kafka messages
    // 处理 Kafka 发来消息的逻辑在这个类中的 run 方法中实现, 
    // 因为 MessageHandler 这个类实现了线程的  Runnable 接口, 
    // 所以在上面构建的线程池中便可以通过调用 submit 方法将其放大待执行的缓冲队列中, 等待线程池中线程出现空闲资源来执行 MessageHandler 类
    // 的 run 方法中执行的逻辑 
    private class MessageHandler(stream:KafkaStream[K,V]) extends Runnable {
        def run() {
            logInfo("Starting MessageHandler")
            try {
            	val streamIterator = stream.iterator() 
            	while (streamIterator.hasNext()) {
            		// 在这里不得不说下 KafkaStream 这个类的定义
            		/**
                       class KafkaStream[K,V] (private val queue:BlockingQueue[FetchDataChunk],
                             consumerTimeoutMs:Int, 
                             private val keyDecoder:Decoder[K],
                             private val valueDedoder:Decoder[V],
                             val clientId:String)
                             extends Iterable[MessageAndMetadata[K,V]] with java.lang.Iterable[MessageAndMetadata[K,V]]
            		*/
            		// 从这个类的定义可以看出, KafkaStream 实际上是继承了 Iterable 的接口的
            		// 而上述代码中的 stream.iterator() 函数调用会返回类型为  IteratorTemplate[MessageAndMetadata[K, V]] 的实例对象
            		// 这样一来便可以使用类似 iterator 的方法来遍历其中的元素了, 而这里的遍历元素实际上也是逐一将数据从 kafka 拉取到 spark 这边

            		val msgAndMetadata = streamIterator.next() 

            		// 这里的 store 方法的实现逻辑是放到了 KafkaReceiver 所继承的父类 Receiver[(K,V)] 这个类中的
            		// 需要注意的是 Receiver 这里关于泛型的定义, 它的泛型是一个由 K 和 V 构成的元组类型作为泛型整体的
            		// 所以在这里也能看到的是 store 函数调用的是将 msgAndMetadata.key 键 和 msgAndMetadata.value 的值
            		// 所构成的元组传入到 store 函数方法中, 同时通过参数类型是一样的也可以判断出 store 这个方法是出自于 Receiver[(K,V)] 这个父类中
            		// 在看过 Receiver 类中所有的 store 方法之后, 我觉得这里调用的 store 方法应该是这一个, 而其中 dataItem:[T] 这个泛型 T 对应的便是
            		// （K,V） 这个由 K 和 V 构成的元组
            		/**
                       Store a single item of received data to Spark's memeory.
                       These single items will be aggregated together into data blocks before being 
                       pushed into Spark's memory.
                       注释: 该方法用于将接收到的小数据项存放到 Spark 内存中, 但是并不是读到一个数据项就将其写入内存, 
                       在写入 Spark 内存之前,通常会将小块的数据聚合到一起, 聚合后打数据块满足阈值之后才将其加载到 Spark 内存空间中
                       def store(dataItem:[T]) = {
	                      supervisor.pushSingle(dataItem)
                       }
            		*/
            		store((msgAndMetadata.key, msgAndMetadata.message))
            	}
            } catch {
            	  // 当 MessageHandler 中的 run 方法被线程池中的线程执行期间出现异常的时候, 会将异常信息记录到日志zhong
                  case e: Throwable => logError("Error handling message; exiting", e)
            }
        }
    }
}

// KafkaInputDStream 功能总结
// 对内提供给了那些实现
// 对外调用该类的其他类而言, 提供了那些实现
