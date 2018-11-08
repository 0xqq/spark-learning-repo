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

    // 此方法首先根据传入的 topic 和 partition 来定位找负责更新相关 topic && partition 指定 offset 的 Leader 节点的 ip port
    // 然后通过调用 right 来获取该 (ip,port) 所构成的元组, 通过 map 函数来调用其相关方法与该 Leader Broker (ip, port) 创建连接 
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

        // 这个方法会根据传入的由 topic 构成的集合来构建消息体, 然后将消息体 TopicMetadataRequest 发送到 Kafka server 端
	// 接收到的消息体类型为 TopicMetadataResponse 实例, 从这个实例中获取其 topicsMetadata:Seq[TopicMetadata] 将转换为 Map 实例然后进行返回
	
	// 接收到的消息体类型为 TopicMetadataResponse 实例, 从这个实例中获取其 topicsMetadata 
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
  
	 def getPartitions(topics:Seq[String]):Either[Err, Set[TopicAndPartition]] = {
             // getPartitionMetadata 函数返回的 right 是 Seq[TopicMetadata] 类型		
	     getPartitionMetadata(topics).right.map { r => 
		 // 在这里注意遍历 Set[TopicAndPartition] 中元素的实例对象
	         r.flatMap { tm:TopicMetadata => 
			 // 在这里访问 TopicMetadata 对象实例
			 // 然后从 TopicMetadata 实例中获取 partitionsMetadata:Array[PartitionMetadata] 成员变量逐一遍历并进行访问
			 // 然后, 将上一层的 TopicMetadata 中的 topic 获取出来
			 // 和本层的 PartitionMetadata 中的 partitionId 获取出传入到 TopicAndPartition 构造方法中
			 tm.partitionsMetadata.map { pm:PartitionMetadata =>
		             // 最后在这里将构造的实例对象进行返回, 最终会通过 map 方法将实例对象构成 Set 结果		 
			     TopicAndPartition(tm.topic, pm.partitionId)
			 }
		   }
	     } 
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

 def getLatestLeaderOffsets(
	 topicAndPartitions:Set[TopicAndPartition]):Either[Err, Map[TopicAndPartition, LeaderOffset]] = 
     getLeaderOfffsets(topicAndPartitions, OffsetRequest.LatestTime)

 def getLeaderOffsets(
     topicAndPartition:Set[TopicAndPartition],
     before:Long
 ):Either[Err, Map[TopicAndPartition, LeaderOffset]] = {
     // 在这里将包含 topic & partition 的信息作为 key, 对应记录该 topic & partition 上的 offset 数值对象构成的 HashMap 进行返回
     getLeaderOffsets(topicAndPartitions, before, 1).right.map { r=>
         // 在这里调用同名不同参的函数 getLeaderOffsets(topicAndPartitions:Set[TopicAndPartition], before:Long, maxNumOffsets:Int)
	 // 因为 kafka 自身 ISR 特点和 replcia 数据的备份, 所以通过返回的结构 Map[TopicAndPartition, Seq[LeaderOffset]]
	 // 能够看出的是每个 topic && partition 都有一个 LeaderOffset 实例构成的 Seq 结构来对应
	 // 而这个 getLeaderOffsets 函数则是将 TopicAndPartition -> Set[LeaderOffset] 的关系转换成 TopicAndPartition -> LeaderOffset 
	 r.map { kv => 
		 // MapValues isn't serializable 
		 kv._1 -> kv._2.head // 在这里我们逐一遍历 kv = (TopicAndPartition, Seq[LeaderOffset])
		 // 将 Seq[LeaderOffset] 的首个元素作为 TopicAndPartition 的 value, 即 (TopicAndPartition, LeaderOffset) 
	 }    
     }
 }

 // 在这个方法中会将 Map[K,V] 抓换为 Map[V,Seq[K]] 的格式	
 // m: Map[TopicAndPartition, (String, Int)]			       
 private def flip[K,V](m:Map[K,V]):Map[V, Seq[K]] = 
    // 首先先将 Map[K,V] 的格式按照 map 的 value 进行分组划分
    // 然后逐一遍历其中的元素数值, Map[TopicAndPartition, (String,Int)] 进行 groupBy((TopicAndPartiton, (String,Int) 中的 (String, Int))
    // 来进行 groupBy 这样就会将所有的 Broker Leader 的 ip 和 port 相同的 TopicAndPartition 划分到一个组中
    m.groupBy(_._2).map { kv => 
      // 这个地方的 kv 对应的是 V -> Map([K,V]), 不过在这里 V 指向的 Map[K,V] 的 Map 中的所有 V 数值都是相同的
      // 然后继续在这里将 Map[K,V] 这个对象通过调用 Map[K,V].keys Set[K], 再通过 .toSeq 方法将 Set[K] 转换为 Seq[K]
      kv._1 -> kv._2.keys.toSeq 	    
      // 在方法的最后所返回的是, Map[V, Seq[K]], 其中 Map 中的每一项 V -> Seq[K] 所表示的含义便是
      // 某个 Broker(ip, port) 是作为 Seq[K] 中的所有 Topic && Partition 对应的 Leader 节点的 地址信息	    
    }
			
 // 这个函数是将传入的记录着每个 topic 细化到 partitionId 的 Set[TopicAndPartition] 
 // 和 before:Long 
 // 和 maxNumOffsets:Int 这个			       
 def getLeaderOffsets (
	 topicAndPartitions:Set[TopicAndPartition],
	 before:Long,
	 maxNumOffsets:Int):Either[Err, Map[TopicAndPartition, Seq[LeaderOffset]]] = {
     // 在这个地方调用 findLeaders 函数将传入的 Set[TopicAndPartition] 
     // 转换为 Map[TopicAndPartition, (String, Int)] 的类型	 
     findLeaders(topicAndPartitions).right.flatMap { tpToLeader => 
         // 然后将 Map[TopicAndPartition, (String,Int)] 进行 flatMap 逐一遍历其中的元素 tpToLeader 
	 // tpToLeader:(TopicAndPartition, (String, Int))  
	 // 在对 flip 方法调用之后, 所得到的 leaderToTP 是 1 个 leader broker 上有多个存放在该 leader broker 
	 // 上的 所有 TopicAndPartition 实体对象    
	 val leaderToTP:Map[(String, Int), Seq[TopicAndPartition]] = flip(tpToLeader)    
         val leaders = leaderToTP.keys // leaders 是将所有记录了 TopicAndPartition 集合的 LeaderBroker 节点的信息 (String, Int)
	 // 在这里创建 1 个 TopicAndPartiton 对应的所有 Leader Broker 的基础信息(ip, port) 和该 Leader Broker 上所存放的
	 // TopicAndPartition 上消费的进度数值 offset 
	 var result = Map[TopicAndPartition, Seq[LeaderOffset]] () 
	 val errs = new Err 
	 // 然后, 将 Set[(String, Int)] 记录的 Leader Broker 的基础信息作为参数传入到 withBrokers 函数中
	 // withBrokers 函数会使用传入的 hostname 和 ip 来构造 SimpleConsumer 并以 SimpleC 实例对象来执行 consumer => 后续的函数逻辑
	 withBrokers(leaders, errs) { consumer => 
              // 将 consumer 实例中成功连接的 LeaderBroker 中的 ip & port 获取出来
              // 构建成元组, 传入到类型为 Map[(String, Int), Seq[TopicAndPartition]] 中来获取这个 Broker Leader 上的
             // 所有 topic & partition Seq[TopicAndPartition] 
            val partitionsToGetOffsets:Seq[TopicAndPartition] = leaderToTp((consumer.host, consumer.port))
            
            // 在这里, 我们将当前 consumer 所指向的 LeaderBroker 上的订阅的所有 topic && partition 映射对逐一通过 map 进行转换一下
            // 从 Seq[TopicAndPartition] 转换为 Map[TopicAndPartition, PartitionOffsetRequestInfo] 
            val reqMap = partitionsGetOffsets.map { tp: TopicAndPartition => 
                 tp -> PartitoinOffsetRequestInfo(before, maxNumOffsets)
	          }.toMap 
 
            // 然后将转换得到的 Map[TopicAndPartition, PartitionOffsetRequestInfo] 传入到 OffsetRequest 这个类中
            // OffsetRequest 消息请求体代码: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/OffsetRequest.scala
            // OffsetResponse 消息回复结构代码: https://github.com/apache/kafka/blob/0.8.1/core/src/main/scala/kafka/api/OffsetResponse.scala 
            // 从 OffsetResponse 代码的构造函数可知, 只有第一个参数 Map[TopicAndPartition, PartitionOffsetRequestInfo] 这个参数需要传入
            // 其余的参数类型均来自于类中的静态变量数值
            /**
               case class OffsetRequest(requestInfo:Map[TopicAndPartition, PartitionOffsetRequestInfo],
                          versionId:Short = OffsetRequest.CurrentVersion,  
                          override val correlatedId:Int = 0,
                          clientId:String = OffsetRequest.DefaultClientId,
                          replicaId:Int = Request.OrdinaryConsumerId)
            */
            // 经过上述一番折腾之后, 在这里我们有了向 kafka server 发送 Offset 请求的消息体: OffsetRequest 
            val req = OffsetRequest(reqMap)	

            // 然后借助于先前创建并初始化好的 consumer 调用 getOffsetsBefore 这个函, 将 OffsetRequest 传入到方法中 
            // 而所返回的消息体是 OffsetResponse 类型的, 
            // OffsetResponse:  https://github.com/apache/kafka/blob/0.8/core/src/main/scala/kafka/api/OffsetResponse.scala
            val resp = consumer.getOffsetsBefore(req)
            // 其中 OffsetResponse 中的 partitionErrorAndOffsets 成员变量的类型为  Map[TopicAndPartition, PartitionOffsetsResponse] 这个类型
            val respMap = resp.partitionErrorAndOffsets

            // respMap 中是我们从 Broker Leader 端获取到的最新 Offset Metadata 相关的信息
            // 有了这些数据之后, 我们开始逐一遍历 partitionsToGetOffsets:Seq[TopicAndPartition] 中的关于 kafka 的 topic && partition 
            partitionsToGetOffsets.foreach { tp:TopicAndPartition => 
                // respMap:Map[TopicAndPartition, PartitionOffsetResponse] 会将当前遍历到的 TopicAndPartition 作为 key 找到
                // 其 Broker Leader 节点上所存放关于该 TopicAndPartition 的 PartitionOffset 的数值信息
                respMap.get(tp).foreach { por:PartitionOffsetsResponse => 
                    if ( por.error == ErrorMapping.NoError) {
                    // 如果当前的 PartitionOffsetResponse 中没有记录异常信息的话, 则进一步访问该消息体中的数据字段
                        if(por.offsets.nonEmpty) {
                          // 这里的 tp:TopicAndPartition, 而, result 类型为 Map[TopicAndPartition, Seq[LeaderOffset]]
                          // 我们将当前的 tp 作为 Map 的 Key, 而对应的 Value 则通过当前的 por:PartitionOffsetResponse
                          // 中的 offset 进行 遍历, 没遍历一个 offset 就构建一个 LeaderOffset 实例, 
                          // 然后不断将 LeaderOffset 实例追加到 Map[TopicAndPartition, Seq[LeaderOffset] 的 Seq[LeaderOffset] 中, 最终全部由相同的
                          // tp:TopicAndPartition 来作为 key 指向
                           result += tp -> por.offsets.map { off =>
                                LeaderOffset(consumer.host, consumer.port, off)
                        } 
                    } else {
                      // 当前的这个 else 分支所对应的是 por.offsets.nonEmpty 分支这里不满足条件  
                      // 则会跑到这里来, 使用 Err:ArrayBuffer[Throwable] 将相关的异常追加到动态数组中
                          errs.append(new SparkException(
                            s"Empty offsets for ${tp}, is ${before} before log beginning?"))
                  }
                } else {
                  // 如果 if 这里走到的是 por.error == ErrorMapping.NoError 这个分支判断不满足条件
                  // 会跑到这里来, 同样使用 Err 实例来接收消息
                   errs.append(ErrorMapping.exceptionFor(por.error))
                }                
              }
            }
            if (result.key.size == topicAndPartitions.size) {
                // 在这个地方 result:Map[TopicAndPartition, Seq[LeaderOffset]]
                // result 中记录的是 1 个 topic && partition 在不同的 Leader Broker 节点上的 Offset 数值
                // topicAndPartitions:Seq[TopicAndPartition] 
                // topicAndPartitions 上所记录的是所有需要追溯 offset 的 topic && partition 的 TopicAndPartition 实例对象
                // 如果二者集合大小相同,则表明所有需要追溯 offset 的 topic  && partition 的 offset 数值均已经追溯到了
                // 将其使用返回类型 Either[Err, Map[TopicAndPartition, Seq[LeaderOffset]]] 的 Right 包装结果并返回 
                return Right(result)
            }
	    }  
      // withBrokers 函数声明中 fn 函数的 body 是由 consumer => ... 构成的
	    // 并且函数正确执行的过程必须是 withBrokers 中构建 consumer 实例被正确无异常创建的时候才会执行 fn(consumer) 的函数调用
	    // 并且 fn() 函数为匿名函数, 参数和返回参数类型均已经给定了, 而实际的执行方法在这里由 consumer => 来指向 
      // 到这里 withBrokers 执行结束, 如果 topic && partition 全部获取到, 直接返回即可
      // 如果, 没有获取到, 会执行到这里, 整理 Throwable 类型的各种异常追加到 Err 中, 使用 Left 包装 Err 将其返回
      // 这个地方获取 result.keySet 的 keySet 对应就是 Seq[TopicAndPartition] 的类型, 和我们传入的 topicAndPartiton 类型相同, 
      // 在这个地方执行一个 diff 操作, 能通过 diff 操作查找到 result.keySet 中有多少不再预期希望查询到 offset 的 TopicAndPartition 集合中
      // 将这些没有获取的 TopicAndPartition 写入到 Err 中进行返回
      val missing = topicAndPartitions.diff(result.keySet)
      errs.append(new SparkException(s"Couldn't find leader offsets for ${missing} "))
      Left(errs)
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
