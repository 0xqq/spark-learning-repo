package org.apache.spark.streaming.kafka 

import scala.annotation.tailrec
import scala.collection.mutable 
import scala.reflect.{classTag, ClassTag}
import kafka.common.TopicAndPartition 
import kafka.message.MessageAndMetadata 
import kafka.serializer.Decoder 
import org.apache.spark.{Logging, SparkException}
import org.apache.spark.rdd.RDD 
import org.apache.spark.streaming.kafka.KafkaCluster.LeaderOffset 
import org.apache.spark.streaming.{StreamingContext, Time}
import org.apache.spark.streaming.dstream._ 

// Reference: https://github.com/koeninger/spark-1/tree/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka


// A stream of {@link org.apache.spark.streaming.kafka.KafkaRDD} where each given Kafka topic/partition corresponds to an RDD partition.
// 对于由 KafkaRDD 对象所构成的数据流中, KafkaRDD 建立了 Kafka 指定 topic 及其下的 partition 与 RDD 分区二者之间的关联. 

// The spark configuration spark.streaming.kafka.maxRatePerPartition gives the maximum number of messages per second that each partition will accept .
// spark 配置中的 spark.streaming.kafka.maxRatePerParition 这个选项是用来控制每个 RDD 的分区/partition 每秒最大可接收的消息条数. 

// Starting offsets are specified in advance, and this DStream is not responsible for committing offsets, so that you can control exactly-once semantics. 
// 通过控制每个分区每秒最大消费消息上限, 我们可以预先设定每个 RDD 数据分区中的起始消费 offset 值, 并且 DStream 也不再担任提交 offset 的工作, 这样一来你便可以控制 exactly-once 的语义了. 

// For an easy interface to kafka-managed offsets, see {@link org.apache.spark.streaming.kafka.KafkaCluster} 
// KafkaCluster 这个类中提供了更加简单操作 kafka offset 的接口方法

// @param kafkaParams 
// Requires "metadata.broker.list" or "bootstrap.servers" to be set with Kafka broker(s) NOT zookeeper servers, specified in host1:port1,host2:port2 form.
// 对于 kafkaParams 参数而言, 需要的是 kafka 集群中 broker 列表, 而非 zookeeper 的服务节点列表

// @param fromOffsets per-topic/partition Kafka offses defining the (inclusive) starting point of the stream 
// fromOffsets 这个参数作用于 Kafka 中每个 topic 的每个分区上, 通过闭区间的方式来指定数据流的起始位移

// @param messageHandler function for translating each message into the desired type 
// messageHandler 这个函数是用来将传入消息转换为需要的类型

// @param maxRetries maximum number of times in a row to retry getting leader's offset 
// maxRetreis 这个参数是用来控制, spark 在读取来自 kafka 的数据流期间如果与 leader 断开连接之后, 尝试与 leader 连接的次数(因为订阅期间 Kafka leader 也有可能因为各种原因进行 leader 的切换)

private[streaming]
class DirectKafkaInputDStream[
K:ClassTag,  // ClassTag 等同于 java 中的 Class 类型
V:ClassTag,  
U <: Decoder[_]:ClassTag,  // 这个地方 U<: Decoder[_]:ClassTag 表示的是, U 这个必须是个 ClassTag 就是一个类的类型, 并且这个类的类型还必须是 Decoder[_] 它的子类才行
T <: Decoder[_]:ClassTag,
R:ClassTag] (
    @transient ssc_: StreamingContext , 
    // @transient 这个关键字表示的是, 如果我对 DirectKafkaInputDStream 这个实例进行序列化的话, 
    // 通过 @transient 这个注解注释的对象是无序将其进行序列化的, 故, 在对这个对象进行反序列化的时候, 这个用 @transient 关键字修饰的词也会无法被反序列化
    val kafkaParams:Map[String, String], // 传给 kafka 的参数列表, key 是参数名称, value 是参数设定的数值
    val fromOffsets:Map[TopicAndPartition, Long], 
    // 该从 Kafka 构建的数据流起始消费位移, 其中 TopciAndPartition 定义了 Kafka 集群中的某一个 分区/topic 的元数据信息
    // 将其作为 key ， 而对应的 value 便是这个 topic 下的某个分区的 offset 的详细信息
    messageHandler:MessageAndMetadata[K,V] => R 
    // 这个 messageHandler 直接就是一个方法了,这个参数支持这样的方法作为参数传入
    // 方法的传入参数为 MessageAndMetadata[K,V] , 返回的类型为 R, 满足这种方法签名的参数允许被传入
	) extends InputDStream[R](ssc_) with Logging {

	val maxRetries = context.sparkContext.getConf.getInt(
		"spark.streaming.kafka.maxRetries", 1)
	// 从传入参数的 StreamingContext 里面获取 SparkContext 实例, 然后从中加载配置项 spark.streaming.kafka.maxRetries
	// 也就是 spark 和 kafka leader 连接实效后重新尝试连接的最大次数
	// 如果配置项中没有设置这个数值, 则返回默认值 1

	protected[streaming] override val checkpointData = 
	    new DirectKafkaInputDStreamCheckpointData 
    // 在这里创建 streaming package 范围才可访问的记录 DStream 消费数据记录的 checkpoint 对象实例

    protected val kc = new KafkaCluster(kafkaParams)
    // 通过传入的 kafka 配置参数列表, 创建 KafkaCluster 对象实例

    // 这个方法用来获取每个分区最大处理消息条数的上限
    protected val maxMessagePerPartition:Option[Long] = {
    	val ratePerSec = context.sparkContext.getConf.getInt(
    		"spark.streaming.kafka.maxRatePerPartition", 0)
    	// 首先从 SparkContext 中读取配置每个分区每秒最大消费消息数目的配置信息, 如果未设置将其数值置位 0 
    	if ( ratePerSec > 0 ) {
    		val secsPerBatch = context.graph.batchDuration.milliseconds.toDouble / 1000 
    		// 如果, ratePerSec 数值非 0， 则从 DStream 中读取出每个 batch 的时间周期大小并将时间单位化为 秒
            Some((secsPerBatch * ratePerSec).toLong)
            // 每个 batch 中每个分区最大消费消息条数 = 消费速率上限 * 每个 batch 周期时长, 即 速率 * 时间 = 数据密度
    	} else {
    		None
    		// 配置项中未加制定, 无法计算消费条数上限, 返回 None 
    	}
    }

    // 当前消费数据位移 = 起始消费位移
    // 类似读取文件的时候, 作为参考的文件起始行的指针
    protected var currentOffsets = fromOffsets


    /**
     我觉得, 能想到 kafka topic/partition <---> RDD/partition 这种映射方法这种思路是很厉害的,
     这个方法设计的也很严谨, 我们都知道 Kafka 有其自身的 LSR , LSR 是一组有 kafka broker 节点构成的一个集合,
     可以这样来理解, 只要是位于 LSR 这个集合中的 kafka broker, 那么它上记录的消费某个 topic 的 partition 便是可信的
     但是, 由于 Kafka 自身的稳定性和 Leader 也是在不断不变动的, 一旦 kafka 的 broker 因为自身波动而被从
     LSR 中剔除, 那么它上记录的 offset 便不可信
     ---
     而下面的这个方法, 便为 spark 提供了从 LSR 中读取 broker 数据 offset 的接口, 所以可以这样理解,
     我们通过这个方法直接访问到的是 Kafka 集群中的 LSR 上的 Offset 
     如果 LSR 中有节点变动, 会进行重试, 重试的最大次数通过前文的 maxRetries 这个配置项来控制, 默认是 1 
     如果, 最后仍未获取到 LSR 会抛出异常信息
    */
    @tailrec
    protected final def latestLeaderOffsets(retries: Int):Map[TopicAndPartition, LeaderOffset] = {
        val o = kc.getLastestLeaderOffsets(currentOffsets.keySet)
        // KafkaCluster 类中的 getLatestLeaderOffset 这个方法会在传入的 TopciAndPartition 集合中找出 LSR 中记录的有效 offset 
        // currentOffsets 的类型是 Map[TopicAndPartition, Long] 所以它的 keySet 是由 TopicAndPartition 构成的集合

        // 这个地方对返回值的处理很有意思, 源于 scala 中支持这样一种语法
        // def func(x:Type):Either[A,B] 这个方法可以返回两种类型的参数, A 和 B, 并且允许返回值类型只能是 A/B 其一/either 
        // 而我们在调用函数得到返回值 x = func(xxx) 的时候, 只需要通过调用 x.isLeft / x.isRight 对应的布尔值真假
        // 便可知道返回值究竟是 A 还是 B , 而在得知返回值类型之后, 通过调用 x.left.get 便可获取该返回值
        // 而, 在这里, KafkaCluster.getLatestLeaderoffsets 函数的返回值是 Either[Err, Map[TopicAndPartition, Seq[LeaderOffset]]]
        if (o.isLeft) {
        	// 没有找到 kafka cluster 中的 LSR， o.isLeft = Err = True 
        	// o.isLeft.get 获取数值, 然后通过 toString 将其转换为字符串
        	val err = o.left.get.toString 
        	if ( retries <= 0 ) {
        		// 然后判断下当前重试次数是否还有剩余, 是的, 你，没有看错, 这里是个递归, 递归深度由初始传入参数 retries 数值决定
        		// 而递归达到出口的时候, 便会抛出一个 Spark 异常信息, 表示重试了 retries 次数, 还是没能获取 kafka cluster 中的 LSR 中的 offset 
        		throw new SparkException(err)
        	} else {
        		// 没达到递归出口，还能继续尝试访问 Kafka Cluster 的 LSR
        		log.error(err) // 仅打印 error 日志
        		Thread.sleep(kc.config.refreshLeaderBackoffMs)
        		// 这里的处理其实就很巧妙了
        		// 我这次, 没获取到 LSR 有可能是 Kafka 因为节点进退场导致了 LSR 切换, 我等待他们主备切换的时长, 之后，我在获取一次
        		// 再次递归, 同时将重试次数 -1 
        		lastestLederOffsets(retries -1)  
        	}
        } else {
        	// 因为是 either 的关系, 所以 o.isLeft = false 的情况下, o.isRight 一定是 true 
        	// 这种情况下是正常获取 kafka LSR 中的 Leader Offset 数值了, 直接通过 o.right.get 获取数值 Map[TopicAndPartition, Seq[LeaderOffset]] 即可
        	o.right.get 
        }
    }

    // limits the maximum number of messages per partition 
    // 在这个地方,我们会综合考虑当前 LSR 中 offset 的情况, 和每个 partition 中最大允许消费的消息条数来更新 untilOffset 的数值
    protected def clamp(
    	leaderOffsets:Map[ToipcAndPartition, LeaderOffset]):Map[TopicAndPartition, LeaderOffset] = {
    	//这里的处理有些复杂, 大致的逻辑是开始 traverse 我们之前通过配置项设置好的, 每个 partition 最大消费的条数, 和传入的 leaderOffsets 中
    	// 所记录的上游数据每个 topic 下的 partition 中的 offset 数值二者的最小值, 然后用它来更新对应 traverse 到的每个 
    	// Map[TopicAndPartition, LeaderOffset] 对应的 LeaderOffset 这个实例, 这个实例便是当前的 TopicAndPartition 接下来将会从对应 kafka topic/partition 中
    	// 读取到 RDD/ partition 中的数据条数, 如果上游数据量很大, 也就是传入的参数 leaderOffsets 中对应topic/partition 中的 offset 数值和目前 spark 起始消费 offset 数值
    	// 相差很大，大于了每次需要消费最大消息条数的上限, 我们便会通过 min 方法将此次从 kafka topic/partition 下拉取的数据条数控制在最大消费条数以下.

    	maxMessagesPerPartition.map { mmp =>
            leaderOffsets.map { case(tp, lo) => 
                tp -> lo.copy(offset = Math.min( currentOffsets(tp) + mmp, lo.offset))
            }
    	}.getOrElse(leaderOffsets)
    } // 返回的是经过限流处理的各个 topic/partition 下的 untilOffset 数值

    override def compute(validTime:Time):Option[KafkaRDD[K,V,U,T,R]]  = {
    	// lastLeaderOffsets(maxRetries) 方法会范围 Kafka LSR 中对应 topic 下每个 partition 当前的 offset 数值
    	// 将其传入至 clamp 方法, 和每个 partition 最大消费数据条数上限比对, 得到的是经由限流处理的本次消费的截止 offset 构成的集合
    	val untilOffsets = clamp(latestLeaderOffsets(maxRetries))

        // 通过如下方法我们构建出 KafkaRDD 的实例对象, 其中
        // 如果阅读 KafkaRDD 对象的构造方法的话, 便会知道对于 offset 而言, 其取值范围为 [currentOffsets, untilOffsets) 
    	val rdd = KafkaRDD[K,V,U,T,R] (
    		context.sparkContext, kafkaParams, currentOffsets, untilOffsets, messageHandler)

        // 在将本 batch 的数值从 kafka 拉取经由 KafkaRDD 映射构建成 RDD 对象之后
        // 我们将当前'文件指针' 移动指向 untilOffsets 上 这样下次会以此次的 untilOffset 闭区间开始进行消费处理
    	currentOffsets = untilOffsets.map( kv => kv._1 -> kv._2.offset)
        Some(rdd) // 将构建好的 rdd 进行返回
    }

    // 因为 DirectKafkaInputDStream 继承至 InputDStream 所以有些方法直接用父类的就行, 不需要重写, 比如 start/stop 
    override def start():Unit = {}

    override def stop():Unit = {}



    // ---- 

    // 从此处开始的处理方法是 KafkaRDD 的 checkpoint 数据记录更新的实现
    // DStreamCheckpointData 负责 DStream checkpoint 数据的记录

    private[streaming]
    class DirectKafkaInputDStreamCheckpointData extends DStreamCheckpointData(this) {
  
        // data 在父类 DStreamCheckpointData 中的定义为
        // protected val data = new HashMap[Time, AnyRef]()
        // 在这里, 我们将 data 转换为 HashMap[Time, Array[OffsetRange.OffsetRangeTuple]] 类型
        // 并将其赋值为 batchForTime 
    	def batchForTime = data.asInstanceOf[mutable.HashMap[Time, Array[OffsetRange.OffsetRangeTuple]]]

    	override def update(time:Time) {
    		// 首先清空 hash map 中的数值
    		batchForTime.clear()
    		// 然后便利 generatedRDD 中的实例
    		// 将 field 1 时间字段保留
    		// 将 kv 的 field 2 数值转换为 KafkaRDD 的 offset 数据元组构成的数组
    		// 然后将其赋值给 batchForTime 这个 hash map key,value 中
    		generatedRDDs.foreach {  kv => 
                val a = kv._2.asInstanceOf[KafkaRDD[K,V,U,T,R]].offsetRanges.map(_.toTuple).toArray 
                batchForTime += kv._1 -> a 
    		}
    	}

    	override def cleanup(time:Time) {}

        // 这个方法是重新加载, 
        // 即, 恢复数据的时候从 batchForTime 这个 hash map 中读取上次消费的记录 offset 作为参考恢复到 generatedRDD 中
    	override def restore() {
    		// this is assuming that the topics don't change during execution, which is true currently 

    		// 首先获取 topic 集合
    		val topics = fromOffsets.keySet 

    		// 然后定位得到当前 kafka 集群 LSR 中对应 topic 的 offset 数值
    		val leaders = kc.findLeaders(topics).fold(
                errs => throw new SparkException(errs.mkString("\n")),
                ok => ok 
    	    )

            // 然后, 再对 hash map batchForTime 按照时间戳进行排序, 
            // 将 batchForTime 中记录的 offset range 数值及时间戳作为构建 KafkaRDD 实例的参数
            // 重新构建 KafkaRDD 对象, 然后将这些对象实例赋值给 generatedRDD 对象实例
    	    batchForTime.toSeq.sortBy(_._1)(Time.ordering).foreach { case (t,b) => 
                logInfo(s"Restoring KafkaRDD for time $t ${b.mkString("[", "," ",")}")
                generatedRDDs += t -> new KafkaRDD[K,V,U,T,R](
                	context.sparkContext, kafkaParams, b.map(OffsetRange(_)), leaders, messageHandler)

    	    }
    	}
    }


}








