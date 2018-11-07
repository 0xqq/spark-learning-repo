package org.apache.spark.streaming.kafka 

import scala.util.control.NonFatal
import scala.util.Random
import scala.collection.mutable.ArrayBuffer 
import java.util.Properties 
import kafka.api._ 
import kafka.common.{ErrorMapping, OffsetMetadataAndError, TopicAndPartiton}
import kafka.consumer.{ConsumerConfig, SimpleConsumer}
import org.apache.spark.SparkException 

/**
 * Convenience methods for interacting with a Kafka cluster.
 * @param kafkaParams Kafka <a href="http://kafka.apache.org/documentation.html#configuration">
 * configuration parameters</a>.
 *   Requires "metadata.broker.list" or "bootstrap.servers" to be set with Kafka broker(s),
 *   NOT zookeeper servers, specified in host1:port1,host2:port2 form
 */

 /**
  KafkaCluster 类中封装了相关方法简化了调用者与 Kafka cluster('s broker) 通信执行步骤. 
  @param kafkaParams Kafka <a href="">configuration parameters</a>
  关于 kafka 相关参数参考这个链接地址
  除此之外, 在使用 KafkaCluster 的时候还需要传入自定义的参数 "metadata.broker.list" 或是 "bootstrap.servers" 这两个参数其中之一, 
  对应这个参数项所给定的参数值是以 host1:port1,host2:port2 格式的 broker 端的 ip,port 数值, 而并非是我们常用的
  zookeeper 的 broker list, 这里的变动是需要注意的.
 */

 private[spark]
 class KafkaCluster(val kafkaParams:Map[String, String]) extends Serializable {
 	// 在同名 object 中所定义的 type 和 case class 如果在 class 中使用的话需要再次 import 
 	import KafkaCluster.{Err, LeaderOffset, SimpleConsumerConfig}

 	// ConsumerConfig isn't serializable 
 	// 在序列化的时候我们不需要对 KafkaCluster 中的 ConsumerConfig 对象实例进行序列化 ?
 	// 不过在这里我不是很清楚为什么需要对 KafkaCluster 进行序列化操作, 为什么它要 extends Serializable ？
    @transient private var _config:SimpleConsumerConfig =  null 

    // 下面这个地方代码是使用同步关键字将整个代码块包括起来, 目的是为了
    // 全局范围内只创建 1 个配置实例对象, 每次在获取对象的时候串行访问
    // 如果对象当前为空, 则重新创建一个对象实例, 如果非空则直接返回
    def config:SimpleConsumerConfig = this.synchronized {
    	if (_config == null) {
    		// 而在这里存放配置项的对象实例, 就是我们在当前这个文件中定义的 ConsumerConfig 的子类 SimpleConsumerConfig 
    		// 我们没有直接调用其构造方法来 new 一个对象出来, 而是调用其同名 object 中的 apply 方法
    		// 传入装满 ConsumerConfig 必须参数, 即我们自定义参数 ""metadata.broker.list" 或是 "bootstrap.servers" 的 HashMap 
    		// 传给其 apply 方法, 通过 apply 方法中的相关执行逻辑, 我们初始化得到 SimpleConsumerConfig
    		_config = SimpleConsumerConfig(kafkaParams)
    	}
        _config 
    }


    /**
     在调用 connect 方法的时候, 实际上我们创建了一个 SimpleConsumer 这个对象实例,
     SimpleConsumer 的代码链接地址是
     https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/consumer/SimpleConsumer.scala
     可以先看下它的构造函数的中传入的每个参数的大概含义
     class SimpleConsumer( val host:String, 连接接收方的 hostname/ip
                           val port:Int,    连接接收方 port
                           val soTimeout:Int, socket 连接超时判定时间
                           val bufferSize:Int, 双方交互数据缓冲区大小
                           val clientId:String) clientId 由 kafka consumer client 作为标识区分不同 client 的 id 
    */
    def connect(host:String, port:Int):SimpleConsumer = 
        new SimpleConsumer(host, port, config.socketTimeoutMs, 
        	    config.socketReceiveBufferBytes, config.clientId)


    def connectLeader(topic:String, partition:Int):Either[Err, SimpleConsumer] = 
        findLeader(topic, partitoin).right.map(hp => connect(hp._1, hp._2))


    // Metadata api 
    // scalastyle:off 
    // https://cwiki.apache.org/confluence/display/KAFKA/A+Guide+To+The+Kafka+Protocol#AGuideToTheKafkaProtocol-MetadataAPI
    // scalastyle:on 
   
    
    // TopicMetadataRequest: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/TopicMetadataRequest.scala
    // findLeader 这个函数, 根据传入的 topic 名称, 和这个 topic 的分区的 ID 数值
    // 返回存放这个 topic 且分区 ID 与传入参数匹配的节点的 hostname && port 值

    def findLeader(topic:Stirng, partition:Int):Either[Err, (String,Int)] = {

    	// 这里首先创建 && 初始化 SimpleConsumer 发送给 Broker 端的消息体结构
    	val req = TopicMetadataRequest(TopicMetadataRequest.CurrentVersion,
    		0, config.clientId, Seq(topic))
    	val errs = new Err // 在这里我们创建好 Err: ArrayBuffer[Throwable]
     
        // 接下来通过调用 withBrokers 函数, 根据传入的多个 broker 节点的 ip,port 数值来构建 consumer 
        // 通过阅读 withBrokers 函数可知, 这个 withBrokers 函数首先构造 consumer, 如果构造成功则将构建好的 consumer 这个实例
        // 传入到用户自定义的函数中, 而这里的自定义函数便是 consumer => 后接的代码逻辑

    	withBrokers(Random.shuffle(config.seedBrokers), errs) { consumer => 
    		// 函数路基中, 首先调用 consumer:SimpleConsumer 的 send 方法将前文构造好的消息体发送
    		val resp:TopicMetadataResponse = consumer.send(req)

    		// 接下来对接收到的消息体进行逐一遍历从中获取想要的信息
    		// 返回的实体对象是 TopicMetdataRequest 
    		// 其中的 topicsMetadata 对应的是一个 Seq[TopicMetadata] 结构, 我们通过 find 的方式来逐一遍历 Seq[TopicMetadata] 中的 TopicMetadata 实例
    		// 对比其 topic 字段和我们需要查找的 topic 字段是否相同, 如果相同的话我们就继续访问这个 TopicMetadata 实例, 
    		// TopicMetadata: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/TopicMetadata.scala
    		// TopicMetadata.partitionsMetadata:Array[PartitionMetadata] 所以继续调用 find 方法, 在 topic = 传入 topic 的 TopicMetadata 中的
    		// partitionMetadata:Array[PartitionMetadata] 中继续找 partitonId 和传入的 partition 数值相等的 PartitionMetadata 
    		// PartitionMetadata: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/TopicMetadata.scala
    		// case class PartitionMetadata(partitionId:Int, val leader:Option[Broker], replicas:Seq[Broker], 
    		// isr:Seq[Broker] = Seq.empty, errorCode:Short = ErrorMapping.NoError) extends Logging 
    		// 这个 PartitionMetadata 中包含的信息量很多, 其中标识了对应 topic 的 partitionId 数值, 和当前处于集群中 Leader 状态的 Broker 信息
    		// 也就是 PartitionMetadata 中的 leader:Option[Broker] 这个对象, Leader Broker 是 Kafka 集群中所有 Broker 的一个子集

    		resp.topicsMetadata.find(_.topic == topic).flatMap { tm:TopicMetadata => 
    			tm.partitionsMetadata.find(_.partitionId == partition)
    			}.foreach { pm:PartitionMetdaata => 
                  pm.leader.foreach { leader =>
                  	  // 如果我们找到这个 Leader 的 host:port 将其构成一个元组, 并使用 Right 进行封装返回即可
                      return Right((leader.host, leader.port))
                  }
    			}
    	    }
    	    // 上述所有操作中的异常类型的信息均会被追加到这个 ArrayBuffer[Throwable] 中
    	    // 最后返回的时候使用 Left 将其封装返回
            Left(errs)
        }
        // 总结一下, 上述的方法中有两个地方比较绕, 一个是 withBrokers 这个函数的调用, 首个参数构建 consumer 对象
        // 第二个匿名函数, 通过 consumer => 的方式来给出实现方式
        // 另一个是, 通过 SimpleConsumerConfig 拦截下来自定义参数 server.brokers.xx 或是 metadata.brokers.xx 其中之一用于传递 broker 节点 ip:port 列表的参数
        // 为自己所用, 其余的传递给 ConsumerConfig 中最终用户构建 SimpleConsumer 对象实例,
        // 创建 SimpleConsumer 对象实例的目的是为了创建和 kafka 的连接, 通过构造消息结构体 TopicMetadataRequest
        // 将消息结构体经由 SimpleConsumer.send 方法进行发送, 接收得到来自 Kafka 服务器端的最新消息 TopicMetadataResponse 
        // 并根据参数中传入的 topic/partitionId 数值遍历 TopicMetdataReponse 中的信息, 首先匹配 topic 相同的所有 TopicMetadata 
        // 然后再从这些 TopicMetadata 中对比 partitionId 数值, 获取到 partitionId 数值等于 传入 partition 的 PartitionMetadata 
        // 最后便会获取到 topic partition 均匹配的 PartitionMetadata 对象实例, 再从这个实例的 Leader 中获取
        // 目前记录着 topic partition 数值的 Leader Broker 节点的 ip 和 port 数值, 将其封装成元组, 使用 Right 包装后返回
        // 此期间的异常均会被 Left 这里包装的 ArrayBuffer[Throwable]:Err 记录, 作为 left 返回
        // ======================================================================================


        // 
        def getPartitionMetadata(topics:Set[String]):Either[Err, Set[TopicMetadata]] = {
        
        	// 首先构造  kafka 服务端能够接受的消息体实例
        	val req = TopicMetadataRequest (
                      TopicMetadataRequest.CurrentVersion, 0, config.clientId, topics.toSeq)
            
            val errs = new Err 

            withBrokers(Random.shuffle(config.seedBrokers), errs) { consumer => 
            	// 将构造好的消息体经由 withBrokers 函数构建好的 consumer:SimpleConsumer 的 send 方法发送至 Kafka 服务器端
                val resp:TopicMetadataResponse = consumer.send(req)

                // error codes here indicate missing / just created topic, 
                // repeating on a different broker won't be useful
                // 在这里存在两种异常点, 一种是 consumer 构建的异常, 这个异常会写入到 errs 中
                // 另一种异常便是 consumer 无法通过调用 send 方法将消息发送至对端的异常, 
                // 为了避免后一种异常, 通过 Random.shuffle 的函数来每次中 broker 列表中随机取出一个节点来构造 consumer 实例
                // 如果返回的结果格式正确, 那么就会将返回结果中的 topicMetadata:Seq[TopicMetadata] 这个成员变量转换为集合进行返回
                return Right(resp.topicsMetadata.toSet)
            } // consumer
            // 如果整个过程均为异常, 则会使用 Left 包装异常信息数组进行返回 
            Left(err)
        }


        // findLeader , 会根据给定的 topic 和 partition 至返回 1 个 Leader Broker 节点, 
        // 而下面这个方法会返回多个 Leader 节点, 以 Map[TopicAndPartition, (String,Int)] 的格式
        // key 是 topic partition ; value 是这个 topic partition 所属于的 Leader Broker 节点的 ip 和 port 数值
        // TopicAndPartiton: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/common/TopicAndPartition.scala
        // 这个 case class 中只有 2 个字段, topic 和 partition 
        def findLeaders ( topicAndPartition:Set[TopicAndPartition]):Either[Err, Map[TopicAndPartition, (String, Int)]] = {
        	val topics = topicAndPartition.map(_.topic) // 获取 Set[TopicAndPartition] 中所有的 topic 集合
        	// 在这里通过 .right 的方式来获取包含所有 topic 的 TopicMetadata 元信息集合
        	val response = getPartitionMetadata(topics).right
        	val answer = response.flatMap { tms:Set[TopicMetadata] => 
        		// 这里逐一遍历 Set[TopicMetadata] 中的元素
        	    val leaderMap = tms.flatMap{ tm: TopicMetadata => 
        	    	// 然后遍历 TopicMetadata 
        	    	tm.partitionMetadata.flatMap { pm:PartitionMetadata => 
        	    	   // 遍历 TopicMetadata Array[PartitionMetadata] 中的各个实例
                        val tp = TopicAndPartition(tm.topic, pm.partitionId)
                        if ( topicAndPartitions(tp)) {
                        	// 这里是判断刚刚使用 tm:TopicMetadata 的 topic 和当前的 pm:PartitionMetadata 实例化的 TopicAndPartition 对象
                        	// 在传入的 topicAndPartition 中是否存在
                        	// 如果存在, 就说明当前的这个 PartitionMetadata 中包含的 partitionId + 上一个遍历层中的 topic 是需要寻找的
                        	// PartitionMetadata 中的 leader 是一个由 Broker 所构成 Option[Broker]
                        	// 我们使用 Broker 中的 host 和 port 这两个成员变量构成的二元组作为 Map 字段的 value 
                        	// 而 Map 的 key 则有 TopicAndPartition 这个实例对象也就是这个 tp 来指向
                        	pm.leader.map { l => 
                                 tp -> (l.host -> l.port)
                        	}
                        } else {
                        	None 
                        }
        	    	}
        	    }.toMap 

        	    if (leaderMap.keys.size == topicAndPartitions.size) {
        	    	// 最终在这里 leaderMap:[TopicAndPartition, (String,Int)]
        	    	// 而 topicAndPartition:Set[TopicAndPartition] 
        	    	// 如果二者的 size 大小完全相同, 则说明, topicAndPartition 中 TopicAndPartition 中记录的 topic, partitionId
        	    	// 均能在集群中找到对应记录该 topic && partitionId 数值的 Leader Broker 节点了
        	    	// 将 leaderMap:[TopicAndPartition,(String,Int)] 作为 Right 返回即可
        	    	Right(leaderMap)
        	    } else {
        	    	// 如果数值不相等, 肯定是因为某些 topic && partitionId 没有找到其所对应的 leader:Broker 这个节点的信息
        	    	// 通过使用 diff 操作来找出来那些 TopicAndPartition 中所记录的 topic && partitionId 没有找到 Broker Leader 信息
        	    	val missing = topicAndPartitions.diff(leaderMap.keySet)
        	    	val err = new Err 
        	    	// 重新构建一个新的 Err 实例, 然后创建一个 Throwable 的对象, 将 missing 的 TopicAndPartiton 
        	    	// 信息作为记录信息写到 SparkException 的消息体中, 然后将这个异常信息追加到 Err:ArrayBuffer[Throwable] 中
        	    	err.append(new SparkException(s"Couldn't find leaders for ${missing}"))
        	    	// 最终使用 Left 将这个 err 信息封装到其中进行返回
        	    	Left(err)
        	    }
        	}
        	// 上述方法中的 Right/Left 会由 answer 接收, 将 answer 作为返回数值进行返回
        	answer 
        }


 }





// =====================================================

// Try a call against potentially multiple brokers, accumulating errors
// 尝试与多个 broker 建立通信, 如果成功便基于建立的通信连接交换收发信息, 如果失败则将失败信息写入到 Err:ArrayBuffer[Throwable] 中
/**
  这里的函数调用是同一个函数 `withBrokers` 后面跟了 2 个参数列表, 这里的第一个 () 对应的是调用函数 withBrokers 时候的参数列表,
  第二个 () 实际上是声明了一个函数参数, 也就是, 你需要创建一个函数满足传入参数类型必须是 SimpleConsumer 类型, 返回值类型任意都可以,
  如 def func(args:SimpleConsumer):Any 这种方式定义的函数, 才行, 
  withBrokers(brokers:Iterable[(String,Int)], errs:Err) 在执行内部逻辑的过程中会在某些地方调用你作为参数传入的函数逻辑
*/
private def withBrokers(brokers:Iterable[(String, Int)], errs:Err)
    (fn:SimpleConsumer => Any):Unit = {
    // 在这里逐个遍历 brokers 中 (String, Int) 元组	
    // 将其中的第一个字符串元素作为 broker 的 hostname,
    // 将其中的第二个整型元素作为 broker 的 port 调用 connect 方法来创建连接
    brokers.foreach { hp => 
        var consumer:SimpleConsumer = null 
        try {
        	// 在这里通过 hostname:port 调用 connect 方法来创建与 broker 的连接
            consumer = connect(hp._1, hp._2)
            // 如果 connect 函数中所建立连接成功, 则会执行下一步
            // fn 是用户自定义传入的满足参数为 SimpleConsumer, 且返回值为 Any 类型的函数
            // fn 中的函数逻辑有用户自己定义实现
            fn(consumer)
        } catch {
        	// 如果 connect 函数中建立连接失败的话, 会抛出一个异常, 抛出的异常会将代码逻辑跳转到 catch 这里
        	// 在这里我们的处理逻辑是将异常信息 e:Throwable 类型的实例, 追加到 ArrayBuffer[Throwable] 这个动态数组中
        	// 也就是这个 Err 定义的相关类型
        	case NonFatal(e) => 
        	    errs.append(e)
        	} finally {
            // 无论上述 connect 函数调用之后连接是否建立成功都会执行 finally 这里
            // 抛出异常则说明 consumer = connect(hp._1, hp._2) 这一步没有执行, consumer 实例对象仍旧为 null 
            if ( consumer != null ) {
            	// 如果 consumer 不为 null, 则说明该实例创建是正常的, 调用其 close 方法关闭其余 broker 二者之间的通信即可
            	consumer.close() 
            }
        }
    }
}

// 在 KafkaCluster object 中定义 Err, 和 LeaderOffset 
// 不过在 KafkaCluster 中定义的 type 和 case class 如果要在 KafkaCluster 类中使用的话必须要 import 后才能正常使用
private[spark]
object KafkaCluster {
	type Err = ArrayBuffer[Throwable]

	private[spark]
	case class LeaderOffset(host:String, port:Int, offset:Long)
}

/**
   High-level kafka consumers connect to ZK. ConsumerConfig assumes this use case.
   Simple consumers connect directly to brokers, but need many of the same configs.
   This subclass won't warn about missing ZK params, or presence of broker params. 
*/
/**
  高阶 kafka consumer 连接 ZK 配置项实例. SimpleConsumerConfig 类定义及这个类中提供的方法功能均复用了 ConsumerConfig 这个类.
  SimpleConsumer 是作者自己定义的类类型, 它会直接和 broker 二者建立通信关系, 
  同时, SimpleConsumer 中所需要的很多的配置信息都来自于 ConsumerConfig 这个类,
  但我们如果想要基于 SimpleConsumer 直接与 broker 二者进行通信的话, 
  我们通过增设这个 SimpleConsumerConfig 作为 ConsumerConfig 的子类, 就是为了在初始化过程中将 zk 相关信息置空也不会引起告警,
  同时将我们自定义的 broker 相关配置信息提取出来用于初始化 SimpleConsumer 这个 Consumer 的子类.
*/
private[spark]
class SimpleConsumerConfig private(brokers:String, originalProps:Properties)
    extends ConsumerConfig(originalProps) {

    val seedBrokers:Array[(String,Int)] = brokers.split(",").map { hp => 
        val hpa = hp.split(":")
        (hpa(0), hpa(1).toInt)
    }
}


/**
 在 SimpleConsumerConfig 对应的 object 的 apply 函数中给出了将传入参数 kafkaParams:Map[String, String] 
 中包含 "metadata.broker.list" 或是 "bootstrap.servers" 字段的配置从 kafkaParams 中提取出来, 
 将其赋值给 brokers 这个 SimpleConsumerConfig 的成员变量, 
 然后将剩余排除掉 "metadata.broker.list" 或是  "bootstrap.servers" 的配置项，遍历写入到 Properties 实例中, 
 在遍历写入之后, 会筛选下 Properties 中是否有包含 "zookeeper.connect", "group.id" 这两个关键字的配置项,
 如果有的话, 将这两个配置项置空, 防止将这两个地方的信息发送到 Consumer 端
 
 最后, 通过刚刚我们从传入的配置参数中提取出的 kafka broker 配置项, 
 和构建并传入参数的 Properties 实例来构建并初始化 SimpleConsumerConfig 这个实例对象
*/
private[spark]
object SimpleConsumerConfig {
	// Make a consumer config without requiring group.id or zookeeper.connect 
	// since communicating with brokers also needs common settings such as timeout 
	def apply(kafkaParams:Map[String, String]):SimpleConsumer = {
		// These keys are from other pre-existing kafka configs for specifying brokers, accept either 
		val brokers = kafkaParams.get("metadata.broker.list")
		        .orElse(kafkaParams.get("bootstrap.servers"))
		        .getOrElse(throw new SparkException("Must specify metadata.broker.list or bootstrap.servers"))

		val props = new Properties()
		kafkaParams.foreach{ case (key, value) => 
		    // prevent warnings on parameteers ConsumerConfig doesn't know about 
		    if ( key != "metadata.broker.list" && key != "bootstrap.servers") {
                props.put(key, value)
		    }
		}

		Seq("zookeeper.connect", "group.id").foreach { s => 
            if ( !props.contains(s)) {
            	props.setProperty(s, "")
            }
		}
	}
}










