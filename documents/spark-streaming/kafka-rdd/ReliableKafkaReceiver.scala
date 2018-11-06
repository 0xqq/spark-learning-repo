// Reference: https://github.com/koeninger/spark-1/blob/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka/ReliableKafkaReceiver.scala
package org.apache.spark.streaming.kafka 

import java.util.Properties 
import java.util.concurrent.{ThreadPoolExecutor, ConcurrentHashMap}

import scala.collection.{Map, mutable}
import scala.reflect.{ClassTag, classTag}

import kafka.common.TopicAndPartition 
import kafka.consumer.{Consumer, ConsumerConfig, ConsumerConnector, KafkaStream}
import kafka.message.MessageAndMetadata 
import kafka.serializer.Decoder 
import kafka.utils.{VerifiableProperties, ZKGroupTopicDirs, ZKStringSerializer, ZkUtils}
import org.I0Itec.zkclient.ZkClient 

import org.apache.spark.{Logging, SparkEnv}
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener, Receiver}
import org.apache.spark.util.Utils 

// ReliableKafkaReceiver offers the ability to reliable store data into BlockManager without loss. 
// ReliableKafkaReceiver 会将数据可靠无丢失地存入到 BlockManager 中

// It is turned off by default and will be enabled when spark.streaming.receiver.writeAheadLog.enable is true. 
// ReliableKafkaReceiver 默认是关闭的, 默认会使用 KafkaReceiver 来接收 kafka 端的数据, 通过将 spark 配置项中的 
// spark.streaming.receiver.writeAheadLog.enable 设为 true 的话, 会开启这个 ReliableKafkaReceiver 功能由它来接收 kafka 端的数据

// The difference compared to KafkaReceiver is that this receiver manages topic-partition/offset itself and updates the offset information 
// after data is reliably stored as write-ahead log. 
// ReliableKafkaReceiver 和 KafkaReceiver 相比不同之处是, ReliableKafkaReceiver 将 topic-partition/offset 由自己来维护, 
// 并且只有当数据接收记录可靠地记录到 WAL 日志中之后, 才会对自己维护的 topic-partition/offset 中的 offset 数值进行更新. 

// ----
// (其实这个地方也正是为一直比较疑惑的地方, 那就是如果处理数据过程中程序出现了异常, 并且因为异常而退出的话, 那么就算是我们能够管理 offset 也无法保证数据记录没错)
// (但是这个地方提出了, 这里记录日志的方式是通过 WAL 来进行记录的, 就算是本次数据计算失败, 但是本次执行计算的记录有记录到 WAL 中, 所以通过这种方式是能够恢复的, 
// WAL 这里的作用可以看做是数据库中的 REDU 日志的记录及恢复点的恢复方式) 关于 WAL 这里的原理有必要系统学习一篇文章
// ---- 

// Offsets will only be updated when data is reliable stored, so the potential data loss problem of KafkaReceiver can be eliminated. 
// 消费数据的 offset 会按照 topic/partition 粒度来进行记录, 而仅仅当消费数据被记录之后 offset 的数值才会发生变更, 
// 所以由 KafkaReceiver 所引发的潜在存在数据丢失问题可以通过 ReliableKafkaReceiver 的引入来解决掉.

// Note: ReliableKafkaReceiver will set auto.commit.enable to false to turn off automatic offset commit mechanism in Kafka consumer. 
// So setting this configuration manually within kafkaParams will not take effect. 
// 注意项: ReliableKafkaReceiver 中会将 kafka 中的 auto.commit.enable 这个选项设置为 false, 这样可以关闭 Kafka consumer 中自动提交 offset 的策略. 
// 所以, 即便是在配置项中将 auto.commit.enable 置位 true, 如果将 ReliableKafkaReceiver 功能开启的话, 这个选项也不会生效. 

// ---- 
// 所以从作者的注释信息中可以得知的是, 他主要在 ReliableKafkaReceiver 做了这些事情
// 1. 新增功能点描述: 以 spark 作为数据的消费者, 它将 consumer 将读取到的数据 offset 自动提交给 kafka 端的这个功能, 即, 通过配置项 auto.commit.enable 这个选项给设置无效了
//  新增功能点说明:
//  这么做的原因是为了防止出现这种情况: 数据从 kafka 被读取到 spark 中之后, spark 对该数据进行计算, 但是计算中途出现失败, 导致针对该段数据的结果没有正确生成
//  如果在这种情况下, spark 作为消费端已接收到数据到本地就向 kafka 回复数据已经接收的话, kafka 会将 offset 指针后移, 下次消费的时候便会从新的数据开始消费
//  而,由 spark 计算出错的数据流会因为 kafka 端的 offset 的自动提交而向后推进, 这样被 spark 计算失败的数据便无法再次被获取计算, 这段数据便会丢失
// 2. 新增功能点描述: 将 spark 作为 kafka 的 consumer 端主动将 offset 消费进度发送给 kafka 由 kafka 来维护的原有方式, 变成了由自己来维护 offset 
//                  同时, 将 offset 升级维护的粒度细化到了 topic.partition 上, 粒度很细, 并且不仅如此, offset 的更新时机是该段数据流的数据被成功
//                  计算得到结果之后, 或是对该数据进行消费的信息这个执行操作被记录到  WAL 中之后(也就是即便数据没有被正常计算, 但是已经将该数据流的计算操作
//                  进行记录, 通过 WAL 可以实现对该数据计算的 replay) 在这种情况下, 在 ReliableKafkaReceiver 中才会选择对自己维护的 offset 中的数值进行更新
// 新增功能点说明: 
//  这么做的原因是为了, 粒度更细地维护每个 topic 每个 partition 下数据实际消费的 offset 数值, 且确保数据不仅仅被成功接收, 而且被 spark 成功处理计算生成结果之后,
//  才会将 offset 后移
// 3. 新增功能点描述及说明: 增加了 WAL 日志记录的功能, 几遍是数据计算出现问题, 因为 WAL 可以将执行消费的操作记录在内, 所以, offset 的数值能够通过对 WAL 记录中的信息里进行
//                  恢复到最后一次成功计算处理的 offset 处开始消费. 
// 关于 WAL 这里对应的论文地址为 http://www.vldb.org/pvldb/2/vldb09-583.pdf 这篇论文中记录了 WAL 的核心思想是很值得阅读一下的
// 不过在了解 WAL 其中基本原理后, 我其实很好奇作者是如何在代码中实现 WAL 的, 以及 WAL '持久化' 到那个外存中

private[streaming]
class ReliableKafkaReceiver[
      K:ClassTag, 
      V:ClassTag, 
      U <: Decoder[_]:ClassTag,
      T <: Decoder[_]:ClassTag](
         kafkaParams:Map[String, String],
         topics:Map[String, Int],
         storageLevel:StorageLevel)
      extends Receiver[(K,V)](storageLevel) with Logging {
    
    // 首先从 kafkaParams 这个 hash map 中获取 key = group.id 所对应的 value 数值
    // 将其赋值给 groupId, 通过该 groupId, 作为 kafka 数据消费者的 spark 而言, 便可知道其所在的数据消费 group  
    private val groupId = kafkaParams("group.id")

    // 设置了一个 kafka consumer 端配置必备的参数选项 key ,
    // 其实也就是设置了一个字符串常量 
    private val AUTO_OFFSET_COMMIT = "auto.commit.enable"

    // 在这个地方我们通过 SparkEnv 这个包含了 Spark 中全部的环境上下文信息对象中来获取 SparkContext 中的配置信息
    // 这个地方就是普通的赋值, 不过这里的 scala 语句为什么要用 def 来修饰？
    private def conf = SparkEnv.get.conf 

    // High level consumer to connect to Kafka 
    // 让 Spark 使用高阶 Consumer 连接器创建与 Kafka 的连接
    private var consumerConnector:ConsumerConnector = null 

    // zkClient to connect to Zookeeper to commit the offsets 
    // 创建 ZkClient 对象实例, 在这里我们使用 ZkClient 中提供的接口方法来与 zookeeper 交互
    private var zkClient:ZkClient = null 

    // A HashMap to manage the offset for each topic/partition, this HashMap is called in 
    // synchronized block, so mutable HashMap will not meet concurrency issue. 
    // 这个 HashMap 中维护的是每个 topic 下的 partition 的 offset 数值信息, 即, key = topic.partition-${id}:TopicAndPartition, value = offset:Long
    // 并且但凡是对 HashMap 的访问, 其访问范围均是是控制在同步 block 范围内的, 所以任何对该 HashMap 的操作都是串行的, 
    // 我们将该 HashMap 设置为 mutable 类型的, 因为控制了访问范围(是在串行环境下的), 所以不会有任何因为并发访问引发的问题
    private var topicParitionOffsetMap:mutable.HashMap[TopicAndPartition, Long] = null 

    // A concurrent HashMap to store the stream block id and related offset snapshot 
    // 支持并发访问的 HashMap, 没错, 就是这个 ConcurrentHashMap 专为并发访问而设计的 HashMap 类型
    // 这个 HashMap 是用来记录将数据流中记录到 block 过程中对应的 block id 的, 以及对应的数据流中的 offset 快照信息进行存储
    // 也可以这么理解, 从 kafka 读取的数据流, 虽然会被加载到内存中进行计算, 但是,也会写入到由 BlockManager 所管理的磁盘上的 block 中
    // 而数据流中的数据究竟目前写入的是哪个 block ? 这个是由 block-id 所标识的, 而数据流中随着数据往 block 也就是由 BlockManager 管理的磁盘对象
    // 不断的写入也会不断通过更新 offset, 而这个不断更新的数据流中的  offset 便是注释信息中所说的 'offset snapshot' 即当前读取的 offset 快照
    // 而上面叙述中的 block-id 在下面这个结构体中便是 StreamBlockId , 或许其封装了额外的信息
    // 而, 每个 value 对应的便是 topic.partition-${id} 粒度的数据流的 offset 这个数值, snapshot offset, kafka 该 topic 下的分区的消费进度的 offset 快照信息
    private var blockOffsetMap:ConcurrentHashMap[StreamBlockId, Map[TopicAndPartition, Long]] = null 

    // Thread pool running the handlers for receiving message for multiple topics and partitions 
    // 下面这个线程池用于处理从上游 kafka 的多个 topic 和 parittion 分区下所读取的数据流中的 Message 进行解析的线程而提供资源而创建的线程池
    // 也就是, 线程池开辟了固定数目的线程资源, 而每次我们把处理消息的逻辑的 MessageHandler 作为 Task 提交到线程池中等待资源调度运行该 Task 
    private var messageHandlerThreadPool:ThreadPoolExecutor = null 


    // 该函数重写/override 了其父类 Receiver[T] 中的 onStart 方法, 在其中加入了针对 Kafka 数据流更加定制化的初始化方法
    override def onStart():Unit = {
        // 打个日志标识下, spark 已经作为 ${groupId} consumer 组的一员开始从上游 kafka 这里订阅消费数据了
        logInfo(s"Starting Kafka Consumer Stream with group: $groupId")

        // Initialize the topic-partition / offset hash map 
        // 初始化以 topic-partition 为 key, 该 topic.partition 下的消费位移 offset 为 value 的 hash map 
        topicPartitionOffsetMap = new mutable.HashMap[TopicAndPartition, Long]

        // Initialize the stream block id / offset snapshot hash map 
        // 初始化 key = stream.block.id ; value = topic.partition.offset  的 hash map 
        blockOffsetMap = new ConcurrentHashMap[StreamBlockId, Map[TopicAndPartition, Long]]() 

        // Initialize the block generator for storing Kafka message. 
        // 初始化 BlockGenerator 这个对象实例, 这个对象实例是用来存放从上游 Kafka 这里拉取/消费/订阅的消息体的
        // 其中, streamId 标识了 stream 的 id, 这个成员变量来自于 ReliableKafkaReceiver 的父类 Receiver[T]
        // 而传入的 GeneratedBlockHandler 这一实例对象中给出了处理从 kafka stream 中拉取的原生数据解码接收, 
        // 并将其转换为 Block 对象等等一系列方法逻辑, 该一整套操作定义在 GeneratedBlockHandler 所集成的 BlockGeneratorListener 这个特质/trait 中
        // 继承该特质, 然后根据 Kafka 数据流的特点给出具体处理逻辑的实现
        blockGenerator = new BlockGenerator( new GeneratedBlockHandler, streamId, conf)

        if (kafkaParams.contains(AUTO_OFFSET_COMMIT) && kafkaParams(AUTO_OFFSET_COMMIT) == "true") {
        	// 因为作者在之前已经提到了, 如果使用 ReliableKafkaReceiver 的话会强制关闭消费端自动提交 offset 的这个功能
        	// 所以这里的判断逻辑是对 kafkaParams 这个参数 map 中检查是否有配置 "auto.commit.enable" 这个参数选项,
        	// 如果有配置这个参数选项的话, 读取该配置项的数值, 如果为 true 的话, 打印报警日志信息, 提醒用户这个功能将会在接下来的逻辑中被强行关闭
        	logWarning(s"$AUTO_OFFSET_COMMIT should be set to false in ReliableKafkaReceiver, " +
        		" otherwise we will manually set it to false to turn off auto offset commit in Kafka")
        }

        // 在后面我们需要创建 Consumer , Consumer 对象依赖于 ConsumerConfig， ConsumerConfig 初始化传入参数为 Properties 
        // 接下来的操作便是将 kafka 的 parameters 这些由用户设置的配置项从 kafkaParams 这个 HashMap 中 foreach 逐个遍历
        // 赋值到 Properties 这个空实例中
        val props = new Properties() 
        kafkaParams.foreach( param => props.put(param._1, param._2))

        // Manually set "auto.commit.enable" to "false" no matter user explicitly set it to true, 
        // we have to make sure this property is set to false to turn off auto commit mechanism in Kafka 
        // 在这里我们在将通过 kafkaParams 初始化的 Properties 对象实例中的 "auto.commit.enable" 这个选项强制置位 false
        // 就算是用户显示设定了这个选项为 true, 我们在这里也会将其强制置为 false, 因为后续代码实现的逻辑必须是以 auto commit 机制关闭为前提的
        // 否则下面的实现逻辑会出现问题(比如, 我们自己维护一套 offset, 然后 kafka 这边也维护一套 offset, 两个 offset 因为数据计算的异常而造成不一致)
        props.setProperty(AUTO_OFFSET_COMMIT, "false")

        val consumerConfig = new ConsumerConfig(props)

        // 然后, 在这里作者再三检查了一下, 传入给 ConsumerConfig 的 Properties 中将 auto commit enable = false 这个选项是否有在 ConsumerConfig 中生效
        assert(!consumerConfig.autoCommitEnable)

        // 打印日志标记下, 接下来开始由 spark 建立和 zookeeper 的通信
        logInfo(s"Connecting to Zookeeper: ${consumerConfig.zkConnect}")

        // 配置参数从 kafkaParams:HashMap -> Properties -> ConsumerConsumer -> 用于创建 && 初始化 ZkClient 对象实例
        zkClient = new ZkClient(consumerConfig.zkConnect, consumerConfig.zkSessionTimeoutMs,
        	    consumerConfig.zkConnectTimeoutMs, ZKStringSerializer)

        // 接下来创建的是线程池, 线程池中维护的常驻/active 线程数目固定
        // 线程数目 = topic 数目, 线程池的名字叫做 "KafkaMessageHandler"
        MessageHandlerThreadPool = Utils.newDaemonFixedThreadPool(
        	topics.values.sum, "KafkaMessageHandler")

        // 开启 BlockGenerator 开始周期性拉取上游数据, 生成 block 
        // 这个地方 BlockGenerator start 方法调用的时候, 是如何从上游拉取数据封装成 Block 对象的需要阅读
        // BlockGenerator 这里的代码才能知道, 这个是衔接 Block/Stream Batch 比较重要的类, 很值得阅读一下
        blockGenerator.start()

        // 根据设定的泛型 U 构建为 kafka key 类型 K 进行数据解码的解码器/Decoder[K]
        val keyDecoder = classTag[U].runtimeClass.getConstructor(classOf[VerifiableProperties])
                .newInstance(consumerConfig.props)
                .asInstanceOf[Decoder[K]]
        
        // 根据设定的泛型 T 构建为 kafka value 类型 V 进行数据解码的解码器/Decoder[V]
        val valueDecoder = classTag[T].runtimeClass.getConstructor(classOf[VerifiableProperties])
                .newInstance(consumerConfig.props)
                .asInstanceOf[Decoder[V]]

        // 这里的处理逻辑和 KafkaReceiver 这里类似
        // 首先来看下 foreach traverse 的 topicMessageStreams 的结构
        //  def createMessageStreams[K,V](topicCountMap: Map[String,Int],
        //      keyDecoder: Decoder[K],valueDecoder: Decoder[V]): Map[String,List[KafkaStream[K,V]]]
        topicMessageStreams.values.foreach { streams => 
        	// 这一层 foreach 遍历的是 Map 中 Seq[List[KafkaStream[K,V]]], 即, 每个遍历的元素 streams 类型为 List[KafkaStream[K,V]]
            streams.foreach {  stream => 
            	// 这一层 foreach 遍历的是 List[KafkaStream[K,V], 即每个遍历的元素 stream:KafkaStream[K,V]
            	// 其实这里的类型也可通过下面的 MessageHandler 这个类的构造方法传入参数的类型来进行推断
                messageHandlerThreadPool.submit(new MessageHandler(stream))
            }
        }
    }

    // 整个程序在退出时, 对线程池中的资源进行释放和回收等收尾操作放到 onStop 函数中
    // 类中各种实例进行释放, 置空(reference = null), 而这里调用 shutdown 方法之后又将实例对象的引用指向了 null 
    // 是为了防止释放的对象引用在程序没有退出期间内, 指向了其他内存空间, 这样在程序退出之后, 
    // 对象引用指向的其他内存空间得不到释放, 从而导致了内存泄漏等问题
    override def onStop():Unit = {
    	if (messageHandlerThreadPool != null) {
    		messageHandlerThreadPool.shutdown()
    		messageHandlerThreadPool = null 
    	}

    	if ( consumerConnector != null ) {
    		consumerConnector.shutdown() 
    		consumerConnector = null 
    	}

    	if ( zkClient != null) {
    		zkClient.close() 
    		zkClient = null 
    	}

    	if ( blockGenerator != null) {
    		blockGenerator.stop() 
    		blockGenerator = null 
    	}

    	if ( topicPartitionOffsetMap != null ) {
    		topicPartitionOffsetMap.clear()
    		topicPartitionOffsetMap = null 
    	}

    	if (blockOffsetMap != null) {
    		blockOffsetMap.clear()
    		blockOffsetMap = null 
    	}
    }

    
    // Store a Kafka message and the associated metadata as a tuple 
    // 在这里我们将 Kafka 的消息和相关的元数据信息构建成一个元组进行存放
    private def storeMessageAndMetadata (
    	msgAndMetadata:MessageAndMetadata[K,V]):Unit = {
    
        // 在这里我们从传入的 msgAndMetadata:MessageAndMetadata[K,V] 对象获取 topic 和 partition 
        // 来构建 TopicAndPartition 实例对象
        val topicAndPartition = TopicAndPartition(msgAndMetadata.topic, msgAndMetadata.partition)
        
        // 然后抽取 key 和其中的 message 字段构建成元组对象, 作为这个 topic.partition 下的
        // 数据信息
        val data = (msgAndMetadata.key, msgAndMetadata.message)

        // 然后从传入参数中读取 topic.partition 下的 offset 两个数值构建元组将其赋值给 metadata 
        val metadata = (topicAndPartition, msgAndMetadata.offset)

        // 现在是看过 BlockGenerator 代码之后回来重新注释这里的逻辑
        // BlockGenerator.addDataWithCallBack 调用时机是: 当前 Block 正在构建期间
        // 数据流不断写入到本地缓存空间, 随数据流不断增加的还有描述该这段数据的元信息 metadata = {topic.partition.offset}
        // 最终这里的 BlockGenerator.addDataWithCallback(data, metadata) ->
        // 会触发 BlockGeneratorListener.onAddData(data, metadata) 这块的方法调用 
        // 而 BlockGeneratorListener 我们通过看 BlockGenerator.scala 这个文件都知道, 它就是一个 trait, 
        // 它的具体执行处理逻辑由实现了它的实现类来定义, 在 ReliableKafkaReceiver 中, 它的实现类便是这个 GeneratedBlockHandler 这个 handler 
        // 
        blockGenerator.addDataWithCallback(data, metadata)
        // 而这里实际的处理逻辑需要看 BlockGenerator 这里的源码才行
    }

    // Update stored offset 
    // 这个是提供一个更新 hash map topicPartitionOffsetMap 中数值的一个方法
    // topicPartitionOffsetMap: mutable.HashMap[TopicAndPartition, Long] 
    private def updateOffset(topicAndPartition:TopicAndPartition, offset:Long): Unit = {
    	topicPartitionOffsetMap.put(topicAndPartition, offset)
    }

    // Remember the current offsets for each topic and partition.
    // This is called when a block is generated.
    // 下面的这个方法是用于记录每个 topic.partition 下当前的 offset 的数值
    // 当 block 构建的时候, 便会调用这个方法
    private def rememberBlockOffsets(blockId:StreamBlockId):Unit = {
    	// Get a snapshot of current offset map and store with related block id.
    	// 获取当前 offset 的快照信息, 然后将其与 block id 相关联进行存储

    	// 在这里调用 mutable.HashMap 的 toMap 方法将 HashMap 转换为 immutable.HashMap 类型
    	// 然后将 immutable.HashMap 整体作为一个 value, 作为该 blockId 的映射元素将其放入到 
    	// blockOffsetMap:ConcurrentHashMap[StreamBlockId, Map[TopicAndPartition, Long]] 这个实例中
    	val offsetSnapshot = topicPartitionOffsetMap.toMap 
    	blockOffsetMap.put(blockId, offsetSnapshot)
    	// 然后, 在这里将这个批次的缓存信息进行清空, 下个批次数据到达之后才开始写入到 topicPartitionOffsetMap 中
    	topicPartitionOffsetMap.clear() 
    }

    // Store the ready-to-be-stored block and commit the related offsets to zookeeper 
    // 在这个方法中会根据已经会被按照 block 进行存储的 offset 进行获取, 然后将相关已经确定的 offset 提交到 zookeeper 中 
    // 而对应的 stream 转换成 block 之后, 便可以理解这个上游的数据已经被 spark 成功计算并持久化有 BlockManager 来管理的 block 了
    private def storeBlockAndCommitOffset(
    	blockId:StreamBlockId, arrayBuffer:mutable.ArrayBuffer[_]
    	):Unit = {
    	// 在这里, 我们将 blokcId 和缓冲数据作为参数传入之后, 
    	// 其中 blockId 相当于对该缓冲区的索引 id 
    	// 将数据转换为 ArrayBuffer 对象之后, 调用 store 方法写入数据
    	store(arrayBuffer.asInstanceOf[mutable.ArrayBuffer[(K,V)]])
    	// 然后我们在通过这个 blockId 对应的这个批次的 offset 进行遍历然后逐一执行提交
    	// 当提交之后, 我们将 blockId 这个数值对从 blockOffsetMap 中进行移除
    	// 而这里的 foreach 这里是为 blockId 所对应的所有元素逐个传入到 commitOffset 函数中
    	// 而这个 commitOffset 函数便是将 每个 topic.partition.offset 数值信息
    	// 通过 zookeeper api 写入到 zk 中
    	Option(blockOffsetMap.get(blockId)).foreach(commitOffset)
    	blockOffsetMap.remove(blockId)
    }
 
    /**
     Commit the offset of Kafka's topic/partition, the commit mechanism follow Kafka 0.8.x's 
     metadata schema in Zookeeper. 
    
     将 kafka 中逐个的 topic.partition.offset 数值提交到 zookeeper 中, 而提交的机制也就是说, 写入 offset 的数据在 zookeeper 上记录的路径
     结构按照 0.8.x 版本的 metadata schema 的格式写入到 zookeeper 端
    */
    private def commitOffset(offsetMap:Map[TopicAndPartition, Long]): Unit = {
    	if ( zkClient == null) {
    		// 在这里我们先判断下刚刚我们所构建的 zkClient 实例对象是否为 null 
    		// 如果为 null 的话, 我们会构建一个 Exception 实例对象
    		// 然后调用 stop 方法, 把日志信息和 Exception 实例对象传入到 stop 函数中
    		val thrown = new IllegalStateException("Zookeeper client is unexpectedly null")
    		stop("Zookeeper client is not initialize before commit offset to ZK", thrown)
    		return 
    	}
    	// 接下来, 我们以元组粒度来遍历 offsetMap:[TopicAndPartition, Long] 中的键值对
    	for (( topicAndPartition, offset) <- offsetMap) {
    		try {
    			     // 构建 ZKGroupTopicDirs 对象实例
                     val topicDirs = new ZKGroupTopicDirs(groupId, topicAndPart.topic)
                     // 然后根据当前消费的 offset 路径地址和分区信息来拼接成 zookeeper 上的路径信息
                     val zkPath = s"${topicDirs.consumerOffsetDir}/${topicAndPart.partition}"

                     // 最后调用 ZkUtils 中的更新节点信息的 API 来将指定 path 下的元数据信息进行更新
                     ZkUtils.updatePersistentPath(zkClient, zkPath, offset.toString)
    			} catch {
    				// 如果在更新 ZK 数据期间抛出异常的话, 将该异常信息进行截获, 然后打印 WARN 类型的信息
                    case e: Exception => 
                        logWarning(s"Exception during commit offset $offset for topic " + 
                        	s"${topicAndPart.topic}, partition ${topicAndPart.partition}", e)

    			}
                // 如果一切正常的话, 便会打印一个 INFO 级别的日志信息
    			logInfo(s"Committed offset $offset for topic ${topicAndPart.topic}, " + 
    				 s" partition ${topicAndPart.partition}")
    	}
    }

    // Class to handle received Kafka message 
    // 这个类是用来对接收到的 Kafka 消息进行处理的, 并且它继承了 Runnable 这个接口,
    // 可以被作为调度的 ''任务'' 被传入到线程池中, 由线程池来进行调度执行这个类中 run 函数中的逻辑
    // 而这个 run 函数中执行的逻辑也是对传入的 KafkaStream[K,V] 进行解析的逻辑

    private final class MessageHandler(stream:KafkaStream[K,V]) extends Runnable {
    	override def run ():Unit = {
    		while (!isStopped) {
    			// isStopped 这个方法是定义在 ReliableKafkaReceiver 的父类 Receiver 中的方法, 如下是 isStopped 在 Receiver 中的相关定义及注释信息
    			/**
                 Check if receiver has been marked for stopping. Use this to identify when the receiving of data should be stopped.
                 def isStopped():Boolean= {
	                 supervisor.isReceiverStopped() 
	                 这个方法会返回上游是否停止接收数据这样一种状态信息, 在这个 run 方法中, 每次读取上游数据之前先通过这个布尔值判断一下
                 }
    			*/

    			try {
                      val streamIterator = stream.iterator()
                      // 在这个地方获取传入对象 stream:KafkaStream[K,V] 的 iterator 
                      while (streamIterator.hasNext()) {
                      	// 在这里逐一遍历 KafkaStream[K,V] 中的元素, 并将该元素传入到 storeMessageAndMetadata 函数中
                      	// 这个函数底层调用的是 BlockGenerator 相关的方法将构建的数据流写入到 block 中
                      	storeMessageAndMetadata(streamIterator.next)
                      } 
    				} catch {
    				  // 遍历或是数据写入过程中所抛出的异常会被截获, 并写入到 ERROR 级别的日志中
                      case e: Exception =>
                          logError("Error handling message", e) 
    			} 
    		}
    	}
    }

    // Class to handle blocks generated by the block generator 
    // 下面定义的这个类, 它实现了 BlockGeneratorListener 中声明定义的方法, 
    // 在 BlockGeneratorListener 中所定义的方法被在 BlockGenerator 中维护的状态机的不同状态下触发调用
    // BlockGenerator 类中的状态机主要有这样几个状态: {Initialized, Active, StoppedAddingData, StoppedGeneratingBlocks, StoppedAll}
    // 映射这样几个BlockGenerator 类中的状态迁移函数: {start(), stop(), addData(data:Any), 
    // addDataWithCallback(data:Any, metadata:Any), addMultipleDataWithCallback(dataIterator:Iterator[Any], metadata:Any),
    // }
    // 有的函数, 如 keep   虽然不会导致状态机中的状态的迁移, 但是在执行的时候会严格检查状态机的状态, 如果状态不满足便不会执行, 
    // ---- 
    // BlockGenerator 类中的函数大致可被划分为这样几个阶段
    // 1. 开始(初始化各种成员变量, 开辟内存空间) 2. 停止(停止各种成员变量, 回收空间, 释放资源)  
    // 3. 不断从上游逐条拉取数据追加到本地缓存区/ 不断从上游逐条拉取数据追加到本地缓存区,并且每次追加完还会调用个回调方法 
    //    / 不断从上游拉取数据, 每次拉多条数据合并成 1 条之后追加到本地缓存区, 每次写完缓存还会掉回调方法
    // 4. blockIntervalMs 时间到达之后, 将本地缓存区积攒下的每个 batch 写入的数据构建一个 Block, 然后生成这个 Block 的唯一标识符 ID 
    //    之后将 Block 放到本地维护的队列中, BlockGenerator 中会专门维护一个线程, 根据 BlockGenerator 中所维护的状态机具体的状态, 一定频率地从队列中取出 Block 
    //    然后将 Block 发送并托管至 BlockerManager 实例中的队列中, 等待线程扫描发送至 BlockManager 
    private final class GeneratedBlockHandler extends BlockGeneratorListener {
        
        // 需要重点观察的是: 这个函数的调用时机: 该函数被调用时正是 1 个 Block 构建期间, 时间位于 blockInterval 时间周期内, 
        // 在这个期间, BlockGenerator 会不断以 batch 为执行时间频率, 从上游 Kafka 集群中拉取数据, 
        // 然后将拉取的数据写到本地缓冲区中, 而所拉取的数据的元数据信息, 映射性地写入到 HashMap 中, 而, 是的, 你没有看错, 这个 HashMap 
        // 便是一直困惑我们的动不动就调用 clear 方法的 HashMap, 代码读到这里其实这里的 clear 函数调用基本上也搞明白了, 
        
        def onAddData(data:Any, metadata:Any):Unit = {
        	// Update the offset of the data that was added to the generator 
        	if (metadata != null) {
        		val (topicAndPartition, offset) = metadata.asInstanceOf[(TopicAndPartition,Long)]
        	    updateOffset(topicAndPartition, offset)
        	}
        }
  
        // ====
        // 关于 clear 的调用, 再看下这段 ReliableKafkaReceiver.scala 这块的代码好了
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
        // rememberBlockOffsets 这个函数, 
        // 调用时机: 1 个新 block 刚刚被创建完成之后,
        // 传入 rememberBlockOffsets 方法的参数:  
        //     StreamBlockId 类型, 来, 回忆一下, 创建 1 个 Block 时: new Block(blockId:StreamBlockId, newBlockBuffer:ArrayBuffer[Any]), 
        //     Block 构造函数中首个参数便是 StreamBlockId, 可以看做 1 个 Block 对象的唯一 ID, 也可以看做是这个 Block 的索引, 
        //     而第二个参数便是缓冲空间, 也就是数据块 ArrayBuffer[Any] 支持存放任何数据类型的缓存空间
        //     所以, 这里传入函数 rememberBlockOffset 函数的 blockId:StreamBlockId 也可以看做是这个 Block 实体的索引, 唯一指定一个 Block 对象, 
        // 在 rememberBlockOffsets 函数中我们其实是建立一种新的映射关系, 那就是 Block 和这个 Block 中各个 batch 收集到的上游数据的元数据信息创建一个 key,value 映射对，
        // Block 与 batch 的 metadata 实际上是 1 对多的关系, 而在这里我们使用 HashMap 将 metadata 统一存放, 构成了 Block 与 HashMap[TopicAndPartition,Long] 是一一 key,value 映射的关系, 
        // 但是, Block 这个对象指向缓存空间很大, 虽然倒是能用来当 key, 所以让 Block 的唯一标识符 StreamBlockId 拿出来作为 key 更合适, 
        // 所以这里传入的就是 Block 的 StreamBlockId 对象,
        // 而这个 value 呢, 就是, 这个 Block 在构建过程中, 每个 batch 的 Topic.Partition.Offset 的数值, 
        // 由因为 1 个 Block 构建所在的 blockInterval 时间段内有很多 batch 到来, 并且每个 batch 也有其各自的元数据信息, 我们统一将这些 batch 的数据写到缓冲区中
        // 而每个 batch 的元数据信息写入到那个定期 clear 的 topicPartitionOffsetMap:HashMap[TopicAndPartition, Long] 中, 
        // 而每次构建 1 个 Block 的时候, 缓冲区中的数据会被移交到 Block 成员变量的引用对象来指向, 而原有的指向缓冲空间的对象引用会指向一个新的 ArrayBuffer[Any] 对象,
        // 随之而来的, 记录这些缓存空间中数据来源信息的 HashMap[TopicAndPartition,Long] 每个 batch 的元数据信息, 在这里的 rememberBlockOffset 函数中, 
        // 便统一调用 .toMap 方法转换为一个 immutable.Map 对象同 Block 一起存放到 [Block, HashMap[TopicAndPartition, Long]] 映射对中, 
        // 完成上一步操作后, 便将 topicPartitionOffsetMap 中的内容和缓冲空间引用指向新的缓冲空间一样, 清空里面的元数据信息. 所以才会调用 clear 方法.
        // ====

        // 也就是构建 block 的过程中, 每次新的缓存信息写入, 该缓存信息所对应的上游 kafka 数据流中的元数据信息
        // 会被封装到 (topicAndPartition, offset) 这个二元组对象中, 其中 topicAndParititon:TopicAndPartition 中记录了这个构成了 Block 的 batch 的
        // 上游 kafka 集群中的哪个 Topic 哪个 Partition 的哪个 Offset, 将这些作为元数据信息经过转换成对应的类型 (TopicAndParititon, Long) 传入至 updateOffset 这个函数中
        //  
        def onGenerateBlock(blockId:StreamBlockId):Unit = {
        	rememberBlockOffsets(blockId)
        }

        // 上面的 addData 是位于 blockInterval 期间积攒数据到缓冲区期间, 将同步追加到缓存区中数据的 metadata:(TopicAndPartition, Long) 
        // 记录到 HashMap 中的操作
        // 而上面的 onGenerateBlock 这个方法是, 每次 blockInterval 时间周期到达之后, 便开始拿着当前积攒的 缓冲区中的数据创建 Block 对象实例
        // 期间, 这个 Block 中的所有的 batch 的 metadata:HashMap[TopicAndPartition, Long] 将会通过构建的 BlockId 唯一指定
        // 被存放到  blockOffsetMap:ConcurrentHashMap[StreamBlockId, Map[TopicAndPartition, Long]] 这个对象实例中进行记录
        // 所以, 在这里开始注意, 现在我们将视角从原先的数据流中的 batch 转移到 Block 上
        // 在 BlockGenerator 中, Block 对象构建好了之后, 会被放到一个 Block 类型的队列中, 就是这个
        // private val blockForPushing = new ArrayBlockingQueue[Block](blockQueueSize)
        // BlockGenerator 中会专门维护 2 个线程, 其中一个线程便是按照一定时间周期地扫描队列中的元素(也就是 Block 对象), 然后将 Block 对象
        // 推送到 BlockManager 中, 执行推送数据操作的方法是 BlockGenerator.keepPushingBlocks 这个方法
        // 而这个方法底层调用的方法是: pushBlock(block:Block) 这个方法会触发一个来自 BlockGeneratorListener 实现类中的 
        // onPushBlock 的这个方法, 也就是下面的这个方法, 在调用的时候, 传入的 2 个参数分别是 Block.StreamBlockId, Block.ArrayBuffer 
        // 也就是一个 ID, 一块数据空间, 其中 ID 我们不是已经在当前实现 BlockGeneratorListener 类中通过一个 HashMap[StreamBlockId, Map[TopicAndPartition,Long]]
        // 的方式记录了么,
        // 所以, 在 Listener 被调用的时候, 我们有 Block.ID 还有 [Block.ID, Metadata] 了, 我们顺着 Block.ID 就可以查到我们维护缓存空间中的专属于这个 Block 
        // 的各个 batch 的元数据信息了, 
        // 所以我们要做的就是 (其实就是这个代码提交者想法)
        // 通过 Block.ID:StreamBlockID -> 到 HashMap[StreamBlockID, Map[TopicAndParittion, Long]] -> 查出来这个 Block 的 Map[TopicAndPartition, Long]
        // 然后把这个 Map[TopicAndPartition,Long] -> 持久化到 Zookeeper 上通过 ZKUtil 等对应的 API 方法
        // 然后 Block 实体被移交给 BlockManager 
        // 记录这个 Block 的 metadata 的 Map[TopicAndPartition, Long] 被从本地缓存也就是这个 blockOffsetMap:[StreamBlockId, Ma[TopicAndPartition, Long]]中移除
        def onPushBlock(blockId:StreamBlockId, arrayBuffer:mutable.ArrayBuffer[_]):Unit = {
        	storeBlockAndCommitOffset(blockId, arrayBuffer)
        }
        
        // 在这个地方, 我大概能理解一点代码提交作者的想法了, 他充分利用了 BlockGenerator/BlockGeneratorListener 中
        // 提供的自定义实现逻辑这里的特点, 配合着 Block 在追加数据, Block 构建, Block 移交给 BlockManager, Block 本地空间释放
        // 这样几个步骤, 在 Listener 中维护两个内存空间, 来存放 Block 的元数据信息, 这两个内存空间一个是 Block 追加数据的时候当前所有的 batch 的 metadata 
        // 另一个是 Block -> 该 Block 中所有的 batch 的 metadata, 等到这个 Block 构建移交给 BlockManager 之后, 我们可以理解成这个 Block 已经被完全接收了
        // 这个时候, 也同步地将 Block 对应的元数据信息持久化到外存空间, 在这里作者通过 ZkUtil API 将元数据更新到 zookeeper 的上面

         
        // 如果说前面的这些回调方法都是处理 BlockGenerator 中不同 Event 的话, 
        // 那么这个 onError 方法便是用来处理当 BlockGenerator 出现异常时回调的记录抛出上下文详细信息及描述异常日志信息的回调方法
        def onError(message:String, throwable:Throwable):Unit = {
            reportError(message, throwable)
        }
    }

    // so, where is the WAL implementation code ? 
}