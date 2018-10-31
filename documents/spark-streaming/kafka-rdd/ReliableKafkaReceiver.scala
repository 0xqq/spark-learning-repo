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


     




      }








