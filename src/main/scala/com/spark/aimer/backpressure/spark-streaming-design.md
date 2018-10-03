## Spark Streaming back-pressure signaling 
## Spark Streaming 反压设计文档

### Table of Contents 
### 目录


#### 1. Introduction: a Receiver's context
#### 1. Receiver 机制
 
#### 2. Data flow of Spark Streaming
#### 2. Spark Streaming 的数据流图
 
#### 3. Problem 
#### 3. 问题描述

#### 4. Goals
#### 4. 期待结果
 
- 4.1 Back-pressure signaling 
- 4.1 反压(调节)机制

- 4.2 Composability
- 4.2 可组合性

- 4.3 Compatibility: how to signal back-pressure 
- 4.3 可组合性之: 如何传递将发压信号

- 4.4 Proposed solution
- 4.4 本文提出的解决方案

#### 5. Requirements 
#### 5. 需求介绍

- 5.1 Failure Recovery 
- 5.1 失败重试 方面的需求

- 5.2 Performance
- 5.2 性能 方面的需求

- 5.3 Maintainability 
- 5.3 可维护性 方面的需求

#### 6. Alternatives
#### 6. 替换方案

 
#### 7. Implementation Details 
#### 7. 实现细节

#### 8. Conrete code chanegs 
#### 8. 具体涉及到的代码变动
----

### 1. Introduction: a Receiver's context 
### 1. Spark Streaming 中 Receiver 机制介绍 
* Data receiving is accomplished by a Receiver, started by a SparkStreamingContext, which receives data and stores data in Spark ( though not in an RDD at this point). 

> 接收数据(流)由 Receiver 这个在 SparkStreamingContext 中被创建启动的对象来负责, 它也负责了 Spark 系统中的数据的接收与存储(目前我们先跳过数据存放和 RDD 二者之间的关系) 

* Data processing transfers the data stored in Spark's BlockManager(BM) into the DStream by creating an RDD. 
> 数据(流)处理过程是将存放在 Spark 系统中的 BlockManager 实例中的数据通过创建一个 RDD 的方式转换成 DStream 对象. 

* You can then apply the usual two operation classes -- transformations and output operations -- on the DStream.
> 接下来你便可以通过调用 'transformations/操作类' 和 'output operations/数据输出类' 这两个常用的操作作用在 DStream 对象上(以实现对数据流的转换与输出结果等操作). 


* There is only one Receiver per input stream.
> Receiver 和 输入数据流是一对一的关系


### 2. Data flow of Spark Streaming
### 2. Spark Streaming 的数据流图 

* In Spark Streaming, when a Receiver is started, a new block is generated every spark.streaming.blockInterval milliseconds, and each block is turned into a partition of the RDD that will eventually be created by the DStream. 
> 在 Spark Streaming 中, 一个 Receiver 对象一旦被启动, 每隔 ```spark.streaming.blockInterval``` 这些毫秒的时间便会构建一个新的 block， 每个 block 被转变为拥有着多个分区的 RDD 对象中的分区之一, 而 DSteram 则由这些有着多个分区每个分区映射 1 个 block 的多个 RDD 所构成.（总结一下: BlockManager 中有多个 block, 每个 block 对应了 1 个 RDD 中的 1 个分区(partition), 而 DStream 则是由多个 RDD 构成的, DStream 通过 forEachRDD 可以遍历每个 RDD, RDD 通过 forEachPartition 可以遍历 每个 Partition）

><b> 总结一下: 在上面这个小段中, 介绍了参数 spark.streaming.blockInterval 这个参数的视角是上游数据流的，是指相隔多久时间段生成 1 个block ， 而这个 block 对应 RDD 中的 1 个partition  </b>


* The number of partitions in the RDDs created by, e.g, a KafkaInputDStream is determined by batchInterval/spark.streaming.blockInterval, where batchInterval is the time interval at which streaming data will be divided into batches( set via a constructor parameter of StreamContext). 
> 上述构成了 DStream 的 RDD 中的分区数目是由(在这里我们假定上游输入数据流对象是 KafkaInputDStream) ```batchInterval/spark.streaming.blockInterval``` 这两个变量相除得到的, 其中```batchInterval``` 是 Spark 系统中将数据流切分成批处理数据块(batch) 的间隔时间, 通过 StreamContext 构造方法传入参数即可.  

> <b>总结一下: 在上面的小段中, 介绍了参数 batchInterval，这个参数的视角是 StreamContext, 是指多久对上游输入的数据流进行一次 batch 切割, 切割 1 次，构建 1 个RDD, 而构建的这个 RDD 中 partition 分区的数目= StreamContext 切割数据流时间间隔/上游生成 1 个block 时间间隔 = 这段期间生成的 block 数目一共有多少, block 的数目便是构建的这个 RDD 中分区的数目 </b>


* For example, if the batch interval is 2 seconds(default) and the block interval is 200ms(default), your RDD will contain 10 partitions. 

> 例如, 如果 StreamContext 切割数据流的时间间隔(batch interval) 是 2s, 而上游数据流生成 1 个 block 的时间间隔是 200ms，那么每次 StreamContext 切割数据流期间处理的 block 数目 = 2s/200ms = 10 个 block ，10 个 block 对应 10 个 partition ， 10 个 partition 构建了 1 个 RDD 

* Once an RDD is created, its processing can be scheduled as a job by the driver's JobScheduler. 
> 一旦 RDD 被构建之后, 那么接下来它的执行过程便可使用 job 来描述, 该 job 便可通过 driver 端的 JobScheduler 对象来调度了.  

* In the current implementation of SparkStreaming and under default configuration, only one job is active(i.e. under execution) at any point of time. 
> 根据目前 SparkStreaming 的实现, 以及系统中默认的配置参数, 在任何的情况下, 只能有 1 个 job 是处于活跃状态(也就是说, 只有 1 个 job 处于运行/计算/处理数据 状态).

* So, if one batch's processing tasks longer than the batch interval, then the next batch's job will stay queued.
> 所以, 如果当前批次中任务的处理时间 > 系统中规定的批处理时间, 那么下一个 batch 的 job 便会因无法及时获取计算资源而排队等待. 

* The reason it is set to one is that concurrent jobs can potentially lead to weird sharing of resources and which make it hard to debug whether there are sufficient resources in the system to process the ingested data fast enough. 

> 这么做(将活跃状态的 job 数目设定为 1)的目的是因为多个 job 的并发会潜在引发一些奇奇怪怪的资源共享, 这会增加程序 debug 的复杂度, 所以即便是在系统资源充足能够并行多个 job 以加快数据处理速度也仍旧将 job 的并发度控制在 1 的原因.
 
> <b>总结一下: job 的并行度 > 1 会在资源共享时引发一些问题, 我猜多半是多个 job 申请资源释放资源的时候造成资源泄露,或是正在使用的资源被释放等, 等并发 + 公共资源常见的问题</b>


* With only one job running at a time, it is easy to see that if the batch processing time is smaller than the batch interval, then the system will be stable. 
> 每次只运行 1 个 job的话, 可以很容易看出当前流处理系统是否稳健: 只要处理 batch 数据的时间小于上游数据切分生成 batch 数据的时间即可(即,数据处理是否能跟得上上游数据生成, 不然的话, RDD 中会缓存太多的 partition 从而导致 OOM，下游数据结果无法及时产出造成数据延迟)

> <b>记录一下: 现在为止,一共引出了 3 个时间名词 <p>spark.streaming.blockInterval:描述上游输入数据流生成 block 的时间间隔</p><p>batch interval: Spark 系统中将数据流切分成批处理数据块(batch) 的间隔时间</p><p>batch processing time:Spark 系统处理 batch 数据花费时间, 是整个流计算引擎吞吐计算能力的体现</b>
 
(接下来介绍了数据流通中的相关约定)

* The data and meta-data flow inside of Spark Streaming is represented in the attached [figure]()
> Spark Streaming 系统中的数据及元数据流图通过[此连接可查]()

* Data flow up to before RDD creation is in bold arrows, meta-data flow is in normal width arrows.
> 在 RDD 构建之前的数据流图使用加粗箭头描述, 元数据流图使用普通箭头描述.

* Private structures are in dotted blocks, public ones use continuous lines. 
> 私有类型结构体使用打点线框描述, 共有结构体使用连续线框描述.

* Queues are represented in red. Those are only explicit queues: e.g. jobSets in JobScheduler, are an array indexed by time in ms, and accessed in the same way.
> 队列的话使用红色标注出来了, 别无他意就是为了表明这些是队列. 比如说, JobScheduler 中的 jobSet 是以毫秒为单位的时间属性作为索引而组织的数组, 也支持队列的方式来访问. 

* Some points of note:
> 需要注意的几点

> * ReceiverSupervisor handles only one Receiver per Dsteram 
> * ReceiverSupervisor 在每个 Dstream 中仅会负责处理 1 个 Receiver 对象 

> * Note the WriteAheadLogManager accesses the same structure, both in the ReceivedBlockHandler and as part of block generation.
> * 需要注意的是, WriteAheadeLogManager 实例每次访问的都是同一个结构体, 这个结构体在 ReceivedBlockHandler 中, 并且也是每次生成 block 的组成成员.


> * As shown, both BlockManager and ReceiverTracker use actors to communicate control and meta-data.  
> * 如图所示, BlockManager 和 ReceiverTracker 两个实例对象中借助 actor 来同步控制信息与元数据


> * Another point of note is that the number of existing streams is fixed: the receivers get started only when on streamingContext.start(). So the context must be started only after all the input DStreams have been created, and once the context has started no new input DStream can be created. 
> * 另外需要注意的一点便是, 允许处理的流的数目是固定的: 数据接收者仅当 streamContext.start() 方法调用的时候才会开始接收处理数据. 所以 streamContext 上下文环境的启动必须要等到上游输入数据构建 DStream 对象之后才能被启动, 而,一旦 StreamContext 的 start 方法调用开启上游数据处理之后, 便不允许新的上游数据输入流构建 DStream 对象了. 

> * Finally, the throttling of [SPARK-1341](https://github.com/apache/spark/pull/945) only applies when Spark is slicing the data into blocks by itself. I.e. there's no throttling possible if blocks are delivered as an Iterator. 
> * 最后, [SPARK-1341](https://github.com/apache/spark/pull/945) 这个 JIRA 中所讨论的数据节流的方法仅仅适用于当 Spark 系统自发将自身数据切分成 block 的场景中. 这也就是说, 如果基于上游外来数据所构建的多个 block 并使用迭代器分发的这种场景下这种(SPARK-1341)限流方法是无法使用的（因为不是 Spark 自身的数据，不是自行切分构建的 block, 外加迭代器这三个不同点）. 



### 3. Problem
### 3. 问题描述
* Data flows into Spark's cache at the ingestion of data, in the block and RDD creation mechanism described above. 
> 上面的数据流图描绘了，数据采集阶段数据在 Spark 的缓存中, 在 BlockManager 数据块中, 以及在 RDD 构建过程中的流动方式. 
> 

* It flows out of Spark's cache as the RDDs are processed and dropped out of cache, or checkpointed. 
> 图中描绘了当数据流从 Spark 的缓存中流出时变为 RDD 对象进行计算处理并脱离缓存, 或是作为检查点落盘.

* The issue is that if data does not flow out of the cache at least fast as data flows in, Spark Streaming's configuration is unstable and will exhaust resources, usually with fan out of memory(OOM) error if the storage involves memory. 
> 将视角拉回到我们的讨论问题域,如果驻留内存的数据无法接近流入内存数据的速率的化, 以及考虑到目前支持 Spark Streaming 的配置项(指的是目前 Spark Streaming 用于限流的参数选项)并不稳定, 很容易将资源耗光, 而资源耗光通常是以 OOM 这种错误异常为表象的, 如果计算过程中牵涉到内存资源的使用的话.(这个地方大概知道一些, 在进行计算的时候, 通常会选择缓存数据结果的 LEVEL 支持内存/磁盘/内存+磁盘 的， 内存速度比较快, 如果中间生成数据量较小,那么通常会选择内存作为存储介质, 这样每次加载中间缓存数据的时候速度会很快, 这里的 involves memory 应该是指数据存储的 LEVEL 这里)

* There are three places where the lifecycle of data in Spark's cache can be lengthened by some form of congestion:
> 存在这么 3 中情况, 在这 3 中情况下, Spark 缓存数据的生命周期会因为某种形式的阻塞而被延长（这里谈到的生命周期变长,其实就是为了说数据存放在内存时间变久,如果上游数据仍旧持续写入会存在耗尽内存空间的问题）

> * block replication being slow 
> * 如果对 BlockManager 中 block 冗余备份时间过久导致
> * WAL writing being slow 
> * WAL 数据写入过慢导致 
> * processing of a given job being slow
> * 当前正在处理的 job 过慢导致 

* The most frequent case, outside of HA configurations, is the third one.
> 排除高可用性配置这个因素除外, 最有可能导致处理数据常驻内存的情况是第三种(job 处理过慢)

* A consequence of this is that numerous guides exist(most prominently Virdata's) to help tune Spark towards a stable and efficient configuration.
> 解决这一系列问题的方法已经() 有助于优化 Spark 稳定的优化的有效配置

* In particular, those guides often aim to optimize for the following equation:
> 特别是，上述的优化方法的出发方向都是处于如下这些目的
``` batch processing < batch interval ```
> 那就是将系统处理 batch 数据的时间减少至 < 系统生成 batch 数据的时间

* The disadvantages of having to maintain this equation are:
> 而这种优化的方向,或者是说处理问题的方法是存在缺陷的:

* 1. Batch processing time does vary based on the amount of data to be processed, and therefore on the signal's throughput: there is more data flowing in the same interval of 2 seconds at 'rush hour', than there is during a low-activity time frame. 
> 1. 批量处理计算数据的时间受参与批处理的数据量级所影响, 而系统自身所处与吞吐量高峰低谷期间影响着相同周期时间内处理上游数据的量级，例如在 2 秒批处理时间窗口期, 处于高峰期时段的系统所处理的数据量要远大于处于低谷期时段所处理的数据量. (这里是说, 虽然我们可调整生成数据的时间, 以及批处理周期的时间, 但是都忽略了系统在某个时间阶段生成数据的密度, 高峰期,低谷期(或者是低活跃率期) 虽然窗口时间相同， 但是数据到达的密度不同，上述的优化方向仅从时间方向考虑, 忽略了相同时间周期不同数据密度的问题)

* 2. A high throughput of data (high batch processing time) should drive the user to adjust resources. Yet it often drives users to adjust the batch interval .
> 2. 数据的高吞吐量(也就是在批处理数据这段时间内处理大量的数据) 应该是由用户通过协调资源来实现. 但是目前常用的方法却是让用户通过调整批处理时间来实现.(在这里作者指出了问题所在, 就是目前调整 batch interval 没有考虑到相同时间内到达数据的密度的)

* 3. Given a stable configuration of Spark Streaming, if the signal's throughput goes up, the equation above is not verified any more, and Spark Streaming breaks down. 
> 3. 如果 Spark Streaming 中的配置项过于定制化(也就是所有资源也好, 处理的数据量也好这些配置项写死了), 一旦系统处理的数据上升, 上述公式的平衡将无法维持, 久而久之(处理更不上输入, 常驻数据不断累加) Spark Streaming 系统便会因诸多问题引发的异常而退出. 
> <b>小结下: 这个段落中心便是围绕着 Problem 来讲的, 而上述的 3 点(disadvantages) 便是围绕 Spark Streaming 在反压提出之前存在的问题来讨论的，以及在没开始细看反压这个原理之前, 我觉得发压类似于电子电路中的反馈信号量, 根据当前系统实际能处理的数据速率, 和上游推送过来的数据速率计算得到目前即能够发挥现有资源, 又不会产生数据积压的参数, 把这个实时的参数刷新到数据接收端用来动态地调整</b>

* Therefore, we aim at a system that:  
> 因此我们希望将系统设计成这样: (在提出了问题之后, 针对问题提出了大致的解决思路)
* 1. Communicates congestion from places where data is flowing slowly, back to the data ingestion point (the ReceiverSupervisor)
> 1. 在数据流动变得缓慢的地方的通信拥塞能够作为反馈信息回传给数据采集模块(也就是 ReceiverSupervisor)

* 2. Lets the Receiver's Executor implement strategies to deal gracefully with that congestion ( for instance : pushback (a.k.a. not consuming data)/throttling, dropping data, sampling strategies) in a modular, configurable way. 
> 2. 我们可以让处理接收数据的 Executor 来实现一个模块, 在这个模块中我们定义不同类型的策略以优雅地应对不同类型的拥塞(例如, 将数据回推(也称作是, 不消费数据)/阻塞数据), 将数据丢包,取样策略等. 

* The overarching goal is for Spark Streaming to remain stable and running ( on a best effort basis) as the thoughput of data changes. 
> 我们解决问题的出发点是在系统在所处理的数据量发生变动时, 尽最大努力来确保 Spark Streaming 计算应用程序平稳运行.

### 4. Goals
### 4. 目标

* This section describes a solution to a similar problem, and important objectives, in a bottom-up fashion, to finally reach our proposed solution in section 4.4.
> 本小节我们通过一种自底向上的思考角度来介绍一个相似问题的解决方案, 并以相同的思考方式来给出我们将会在 4.4 小节中提出上述问题的最终解决方法.   

* You can jump there if you want to go top-down. 
> 当然, 如果你习惯自顶向下的思维方式的话, 你直接跳到 4.4 小节看吧.

#### 4.1 Back-pressure signaling 
#### 4.1 反压 信号量

* The intent is, for starters, for the [JobScheduler](https://github.com/apache/spark/blob/master/streaming/src/main/scala/org/apache/spark/streaming/scheduler/JobScheduler.scala) to start monitoring the length of the queue and request new elements based on that. To that effect, we suggest one source for inspiration: 

> 我们这么做的目的便是, 对于初学者而言他需要知道 [JobScheduler](https://github.com/apache/spark/blob/master/streaming/src/main/scala/org/apache/spark/streaming/scheduler/JobScheduler.scala) 是通过开始监控队列的长度并且根据队列当前的长度来决定是否请求新的元素入队的. 我们将这一点(也就是 JobScheduler 中的队列长度作为控制是否请求新数据/元素) 来作为我们的思考出发点(灵感之源的翻译有点傻...所以我还是不用了...):

* [Reactive Streams](http://www.reactive-streams.org/) （oh my god , is this the jdk9's new feature? =V=) is an initiative to provide a standard for asynchronous stream processing with non-blocking back pressure. 
> [Reactive Streams](http://www.reactive-streams.org/) Reactive 流是一种支持非反压的标准的自主式异步流处理方法. 

* It consists of a [specification](https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.0.RC5/README.md) (comprised of four classes and some 30-odd rules) which aims at achieving high throughput and resiliency. 
> Reactive Stream 由一系列规范(包含 4 个类, 和其余的 30 个规则构成) 目的就是为了能够在实现系统的高吞吐及其自身的弹性. 

* This specification describes the safe and fast communication of data between a Publisher and a Subscriber. 
> 所制定的这一些列规范目的是用来确保数据发布者和订阅者二者之间数据安全且高速地通信. 

* Though we will just take inspiration from some of the design principles of the Reactive Streams specification, we do not intented for Spark's internals to comply with this specification. 
> 当然了, 我们讨论 Reactive Stream 的目的是从中获取一些解决方法的思路/灵感, 我们可并不会考虑在 Spark 内核中实现上述的规范. 

* However, we think some relevant principles in Reactive Streams are: 
> 而是, 我们会顺着 Reactive Streams 中解决类似问题而提出的相关规则这个思路出发来思考: 

* 1. The whole specification works with a limited-size buffer on the subscriber side. In that specification, the Subscriber sends a back-pressure signal that quantifies the congestion it's facing. 
> 1. 上述全部说明都是在这样一个前提下提出的: 数据订阅端所处理的数据的缓存空间是大小固定的. 在上述规定中(Reactive Stream ), 数据订阅端会发送一种称之为'反压信号量' 的消息,在这个消息中包含了数据订阅端所处理数据时所面临的拥塞程度进行量化后的数值. 

* 2. The receiving of objects is bounded by the Subscriber's side. But within this bound, the Publisher can send elements as fast as it can. This leads to the mnemonic: "Reactive Streams are push-based when the Subscriber is faster, pull-based when the Publisher is faster"  
> 2. 接收数据的速率由数据订阅端来控制. 将数据接收速率控制权放到数据订阅方这一段的话, 数据发布端便可在其能力范围内尽可能快递发送数据了(而不用担心数据订阅方是否应付的来). 从此便有了这样一种说法: "Reactive Stream 如果当数据订阅方处理速度更快的话, 数据便是基于推送的方式传递给数据订阅方的, 如果数据发布方产出数据速率更快的话, 那么数据对于订阅方而言便是基于拉取的方式获取的"

* 3. That signaling discipline is asynchronous, so that the Reactive Streams API prescribes that all processing of the back-pressure signal must not block the Publisher. However, the Publisher can transmit data synchronously or asynchronously.
> 3. 而那种通过信号来控制数据传输方式的则是基于异步机制的, 以至于 Reactive Streams 中的 API 指定了所有基于反压 信号的数据处理方法千万不可将数据发布者阻塞. 而对于数据发布者这一方而言, 数据的同步，异步传输都是支持的


#### 4.2 Composability 
#### 4.2 (发压机制的) 模块化可行性

* Back-pressure signaling composes, so that back-pressure signaling can proceed from back-pressure information:
> 在这里我们讨论一下反压信号量的可组合性, 以便于反压信号量可以在节后到反压信息后能运行生效:

* 1. vertically, further down the stream ( such as block replication reacting on information from the job scheduler's queue).
* 2. horizontally, at different points down the stream. E.g. the BlockGenerator can tailor its action to back-pressure information coming from both the WAL and block replication, with data feeding into those two areas in a fan-out fashion. 
 
