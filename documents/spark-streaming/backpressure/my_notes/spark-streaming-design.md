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
- 4.3 可组合性之: 如何将反压作为信号传递

- 4.4 Proposed solution
- 4.4 本文提出的解决方案

#### 5. Requirements 
#### 5. 需求

- 5.1 Failure Recovery 
- 5.1 失败重试  

- 5.2 Performance
- 5.2 性能 

- 5.3 Maintainability 
- 5.3 可维护性 

#### 6. Alternatives
#### 6. 备选方案

 
#### 7. Implementation Details 
#### 7. 实现细节

#### 8. Conrete code chanegs 
#### 8. 具体涉及到的代码变动
----

<l>NOTE: 翻译的内容部分地方我自己结合理解和上下文关系擅自加上的,目的为了让原文表述更明白, 有时会因为理解不到位引起错误,可能会给阅读者带来误解,所以如果真有人阅读到这里的话,遇到错误请及时告知,或是带着批判,辩证思考的态度来阅读）
---

### 1. Introduction: a Receiver's context 
### 1. Spark Streaming 中 Receiver 机制介绍 
* Data receiving is accomplished by a Receiver, started by a SparkStreamingContext, which receives data and stores data in Spark ( though not in an RDD at this point). 
* <b>接收数据(流)由 Receiver 这个在 SparkStreamingContext 中被创建启动的对象来负责, 它也负责了 Spark 系统中的数据的接收与存储(目前我们先跳过数据存放和 RDD 二者之间的关系)</b> 
<p/>
* Data processing transfers the data stored in Spark's BlockManager(BM) into the DStream by creating an RDD. 
* <b>数据(流)处理过程是将存放在 Spark 系统中的 BlockManager 实例中的数据通过创建一个 RDD 的方式转换成 DStream 对象. </b>
<p/>

* You can then apply the usual two operation classes -- transformations and output operations -- on the DStream.
* <b>接下来你便可以通过调用 'transformations/操作类' 和 'output operations/数据输出类' 这两个常用的操作作用在 DStream 对象上(以实现对数据流的转换与输出结果等操作). </b>
<p/>

* There is only one Receiver per input stream.
* <b>Receiver 和 输入数据流是一对一的关系</b> <p/>


### 2. Data flow of Spark Streaming
### 2. Spark Streaming 的数据流图 

* In Spark Streaming, when a Receiver is started, a new block is generated every spark.streaming.blockInterval milliseconds, and each block is turned into a partition of the RDD that will eventually be created by the DStream. 
* <b>在 Spark Streaming 中, 一个 Receiver 对象一旦被启动, 每隔 ```spark.streaming.blockInterval``` 这些毫秒的时间便会构建一个新的 block， 每个 block 被转变为拥有着多个分区的 RDD 对象中的分区之一, 而 DSteram 则由这些有着多个分区每个分区映射 1 个 block 的多个 RDD 所构成.（总结一下: BlockManager 中有多个 block, 每个 block 对应了 1 个 RDD 中的 1 个分区(partition), 而 DStream 则是由多个 RDD 构成的, DStream 通过 forEachRDD 可以遍历每个 RDD, RDD 通过 forEachPartition 可以遍历 每个 Partition）</b><p/>

* <b> 总结一下:</b> 在上面这个小段中, 介绍了参数 spark.streaming.blockInterval 这个参数的视角是上游数据流的，是指相隔多久时间段生成 1 个block ， 而这个 block 对应 RDD 中的 1 个partition


* The number of partitions in the RDDs created by, e.g, a KafkaInputDStream is determined by batchInterval/spark.streaming.blockInterval, where batchInterval is the time interval at which streaming data will be divided into batches( set via a constructor parameter of StreamContext). 
> 上述构成了 DStream 的 RDD 中的分区数目是由(在这里我们假定上游输入数据流对象是 KafkaInputDStream) ```batchInterval/spark.streaming.blockInterval``` 这两个变量相除得到的, 其中```batchInterval``` 是 Spark 系统中将数据流切分成批处理数据块(batch) 的间隔时间, (这个间隔时间可以)通过 StreamContext 构造方法传入参数来设定.  

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
 
(接下来介绍了数据流中的相关约定)

* The data and meta-data flow inside of Spark Streaming is represented in the attached [figure](https://github.com/Kylin1027/spark-learning-repo/blob/master/documents/backpressure/png/backpressure1.png)
> Spark Streaming 系统中的数据及元数据流图通过[此连接可查](https://github.com/Kylin1027/spark-learning-repo/blob/master/documents/backpressure/png/backpressure1.png)

* Data flow up to before RDD creation is in bold arrows, meta-data flow is in normal width arrows.
> 在 RDD 构建之前的数据流图使用加粗箭头描述, 元数据流图使用普通箭头描述.

* Private structures are in dotted blocks, public ones use continuous lines. 
> 私有类型结构体使用打点线框描述, 共有结构体使用连续线框描述.

* Queues are represented in red. Those are only explicit queues: e.g. jobSets in JobScheduler, are an array indexed by time in ms, and accessed in the same way.
> 队列的话使用红色标注出来了, 别无他意就是为了表明这些是队列. 比如说, JobScheduler 中的 jobSet 是以毫秒为单位的时间属性作为索引而组织的数组, 也支持队列的方式来访问. 

* Some points of note:
> 需要注意的几点

> * ReceiverSupervisor handles only one Receiver per Dsteram 
> * ReceiverSupervisor 在每个 Dstream 中仅会负责处理 1 个 Receiver 对象(Receiver input stream 前文中有提到是一一对应的关系)
> * Note the WriteAheadLogManager accesses the same structure, both in the ReceivedBlockHandler and as part of block generation.
> * 需要注意的是, WriteAheadeLogManager 实例每次访问的都是同一个结构体, 这个结构体在 ReceivedBlockHandler 中, 并且也是每次生成 block 的组成成员.
> * As shown, both BlockManager and ReceiverTracker use actors to communicate control and meta-data.  
> * 如图所示, BlockManager 和 ReceiverTracker 两个实例对象中借助 actor 来同步控制信息与元数据


> * Another point of note is that the number of existing streams is fixed: the receivers get started only when on streamingContext.start(). So the context must be started only after all the input DStreams have been created, and once the context has started no new input DStream can be created. 
> * 另外需要注意的一点便是, 允许处理的流的数目是固定的: 数据接收者仅当 streamContext.start() 方法调用的时候才会开始接收处理数据. 所以 streamContext 上下文环境的启动必须要等到上游输入数据构建 DStream 对象之后才能被启动, 而,一旦 StreamContext 的 start 方法调用开启上游数据处理之后, 便不允许新的上游数据输入流构建 DStream 对象了. 

> * Finally, the throttling of [SPARK-1341](https://github.com/apache/spark/pull/945) only applies when Spark is slicing the data into blocks by itself. I.e. there's no throttling possible if blocks are delivered as an Iterator. 
> * 最后, [SPARK-1341](https://github.com/apache/spark/pull/945) 这个 JIRA 中所讨论的数据节流的方法仅仅适用于当 Spark 系统自发将自身数据切分成 block 的场景中. 这也就是说, 如果基于上游外来数据所构建的多个 block 并使用迭代器分发的这种场景下这种(SPARK-1341)限流方法是无法使用的（因为不是 Spark 自身的数据，不是自行切分构建的 block, 外加迭代器这三个不同点）. 

![image](https://github.com/Kylin1027/spark-learning-repo/blob/master/documents/backpressure/png/backpressure1.png)

### 3. Problem
### 3. 问题描述
* Data flows into Spark's cache at the ingestion of data, in the block and RDD creation mechanism described above. 
> 上面的数据流图描绘了，数据采集阶段数据在 Spark 的缓存中, 在 BlockManager 数据块中, 以及在 RDD 构建过程中的流动方式. 

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
> 2. 我们可以让处理接收数据的 Executor 来实现一个模块, 在这个模块中我们定义不同类型的策略以优雅地应对不同类型的拥塞(例如, 将数据回退(也称作是, 不消费数据)/阻塞数据), 将数据丢包,取样策略等. 

* The overarching goal is for Spark Streaming to remain stable and running ( on a best effort basis) as the thoughput of data changes. 
> 我们解决问题的出发点是在系统在所处理的数据量发生变动时, 尽最大努力来确保 Spark Streaming 计算应用程序平稳运行.

### 4. Goals
### 4. 目标

* This section describes a solution to a similar problem, and important objectives, in a bottom-up fashion, to finally reach our proposed solution in section 4.4.
本小节我们将以一种自底而上的思维角度来对上述问题相似的问题进行描述与重要目标的定位,这种思维方式与我们在第 4.4 小结所提出的问题定位和解决方法相类似. 
(作者的意图就是,先立一个小目标(...)然后使用自底而上的思考角度来分析定位这个问题, 然后把相同的思考方式平行移动到我们面临的相对较复杂的问题上,使用相同的逐步思考的方式, 得到最终的解决方法, 而最终的解决方法在 4.4 小结介绍)

* You can jump there if you want to go top-down. 
如果你不习惯这种思考方式(in a bottom-up fashion)而是想直接从顶之下思考问题解决方法的话, 可以直接(通过链接)跳到第 4.4 章

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
#### 4.2 (反压)可组合性

Back-pressure signaling composes, so that back-pressure signaling can proceed from back-pressure information: 
反压信号调用方法是可组合的, 以至于根据反压信息便可触发产生反压信号量方法的调用.

* vertically, further down the stream (such as block replication reacting on information from the job scheduler's queue). 
* 顺着数据流来纵向思考下,比方说数据块冗余备份这一操作环节会影响到 job 调度器的队列中存放的信息内容.

* horizontally, at different points down the stream. E.g. the BlockGenerator can tailor its action to back-pressure information coming from both the WAL and block replication, with data feeding into those two area in a fan-out fashion. 
* 如果顺着数据流进行多角度全方位发散思考的话. 例如, BlockGenerator 模块能够根据从 WAL日志写操作 和 数据块冗余备份操作而生成的反压信息作为反馈数据来调整自己下一步的操作，

* In particular, a stream can have a fan-out configuration in which the data ( or a version of it, modulo a map operation) flows into two locations in parallel. 
* 在特殊的情况下, 数据流能够将配置信息分散开，并将负载的配置信息平行地同步至不同的目的地上. 


* Reactive streams support this operation, and for measuring progress on block replication and WAL, in the late stages of this epic, we intend to implement one.
* 响应式数据流中支持上述的操作, 并且能对数据库冗余备份和 WAL 日志写入过程进行测量, 在本段落叙述的最后,我们也会尝试实现一个 'Reactive stream'.

More details can be found in akka-streams documentation. 
更多的实现细节可以参考 akka-streams 相关文档.(批注: 在这篇设计文档被设计出来的时候, Spark 整个系统中的网络模块还是基于 akka 实现的, 直到后续 Spark 版本中 network 模块才逐步替换为 netty 框架,所以在这里仍可见 akka 编程框架的相关文档)

#### 4.3 Compatibility: how to signal back-pressure 
#### 4.3 兼容方面的考虑: 如何将反压信息信号化

Spark Streaming produces one batch at every batch interval, and one block at every block interval. 
Spark Streaming 每隔批处理周期的时间间隔都会执行一次批处理计算, 并且每个批处理间隔时间内会生成一个数据块. 

These constrains are fixed, and will be kept constant, if only because of backwards-compatibility. 
只要是反压能够很好的兼容到 Spark Streaming 系统中, 在处理上述约束方面不做出任何修改. 

Therefore, the best way of descreasing the pressure created by new elements flowing in the stream is to deliver less elements of the stream per time interval. 
因此, 对不断接收处理上游数据流的系统而言最好的降压方式便是在每个时间间隔周期内减少上游流入汇聚到当前数据流中的数据元素才行.(也就是降低单位时间的数据密度,时间间隔不变, 减少时间间隔内流入系统中的数据量)

This means that our adjustment variable will be the number of stream elements per block 
这样也就是说, 我们需要调整的(并不是时间间隔的长短而)是每个 block 中数据流元素的数目.(这里总结下, block 是每个 batch interval 这个时间周期系统接收上游流入的数据而构建的数据对象, 这个 block 本身便带有 batch interval 属性)

Note that the proposed strategies for dealing with congestion:
而我们所提出的应对拥塞的策略有这些: 

pushback(a.k.a. throttling at the block interval)
数据的回退, （就是将每个 batch interval 都构建一个 block 这个操作阻塞掉）

dropping data 
将数据丢弃（这个就是系统/spark 因为处理不过来数据，从数据源开始不接收数据）

sampling 
采样接收数据(就是 100 条数据,根据不同的采样方法只获取其一部分很少量能够代表数据特征的数据)


Can all be applied in this case - they are just applied under the constant clock ticks of the batch and block intervals. 
你可能会问上述解决策略是否能够解决我们面临的问题, 只要将其实现在每个批次和构建数据块的周期间隔内给出实现即可. 

Moreover, this means our implementation does not intend to respect the Reactive Streams specification. 
不仅如此, 这也意味着，在我们提出的实现方法中并不会完全遵照 Reactive Streams 说明中的相关规范. 

In particular, rule 1.1. states: 
特别是, Reactive Stream 中的 1.1 条规则说明: 

<b>The total number of onNext signals sent by a Publisher to a Subscriber MUST be less than or equal to the total number of elements requested by that Subscriber's Subscription at all times.</b> 
<b>由数据发布者/Publisher 通过调用 onNext 信号量函数向其中之一的数据订阅者/Subscriber 所发送的数据条数必须小于等于数据订阅者/Subscriber 的在订阅过程中/Subscription 请求数目的总条数.</b>

Rather, when we say we intend to communicate back-pressure above, we will implement:
我们不遵照上述的 1.1 规则说明是指当我们在实现反压消息通信时不遵照, 我们这样来实现:

<b>The total number of elements per block produced by a BlockGenerator to a Subscriber MUST be less than or equal to the total number of elements last requested by that Subscriber's Subscription at all times.</b>
<b>由每个 BlockGenerator 模块而生成并传递给下游其一数据订阅者(Subscriber)元素总数必须要小于等于这个数据订阅者(Subscriber)在订阅过程(Subscription)建立之初所请求的数据元素总条数.</b>


#### 4.4 Proposed Solution 
#### 4.4 提出的解决方案

We aim to 
我们的设计目标是

* add back-pressure information flowing from element accounting in jobsets in the JobScheduler to the BlockGenerator. The information will be an account of the number elements processed within a fixed interval, and will serve to set a bound on the number of elements per block. That bound may or may not be enforced based on configurable strategies. 

* 将发源于 JobScheduler 中的 job 集合元素信息作为反压信息传递给 BlockGenerator 模块. 所传递的反压信息会作为在固定时间间隔内会被处理的一系列数据元素, 这些数据也会作用于限制每个 block 中容纳数据元素的上限阈值. 这个阈值上限根据配置策略的不同或许会或许不会作为强制限制 block 中最多可容纳数据条数的阈值上限.

* offer an API which feeds data into the BlockGenerator based on the backpressure information. - the default will maintain the exact current behavior (ignoring back-pressure) - a new option will be pushback ( reading elements from the signal until either the block interval has elapsed or the requested number of elements has been attained), equivalent to throttling. 
 
* 我们会实现一个 API 借助该 API 我们可以基于反压信息作为生成反馈数据并传递给 BlockGenerator 模块. 默认的 API 会维护确定性的当前应采取的操作(会忽略掉反压信息); 另一个 API 提供数据回退的操作(所谓数据回退指的是持续从信号调用方法中所读取的数据元素知道数据块切割时间间隔过滤或是数据订阅方所接收到的数据达到其所请求的数据条数), 数据的回退操作等同于数据订阅操作给阻塞掉（暂停订阅数据）.

* offer at the same emplacement three additional implementations of a strategy for input management when the number elements permission is below that of available data.
* 当上游数据接收处理端所接收上游数据元素个数要大于所请求的数据元素个数时,有如下 3 中处理策略:
* a. dropping data 
* a. 直接将接收到的数据进行丢弃
* b. random sampling 
* b. 随机采样接收数据
* c. interfacing with a Reactive-Streams compliant Subscriber provided as a Receiver implementation.
* c. 将数据接收者 Receiver 遵照 Reactive-Streams 接口规范中的 Subscriber 实现方式来处理数据.

* add back-pressure information flowing from block relication at the BlockManager to the BlockGenerator, and compose that information on the speed of block replication to that coming from other sources. 
* 将由数据库冗余备份而生成的反压信息传递给 BlockGenerator, 并将来自其他数据源构建的 block 的在执行冗余备份操作时数据处理的速度数据合成.
* add back-pressure information flowing from WAL writing to the BlockGenerator, composing that information with that of the sources described above. 
* 将 WAL 执行过程中的相关信息作为反压信息回传给 BlockGenerator, 并将其来自于上述不同数据源所生成的反压信息进行合成. 
* Task 1.2. and 3. are the main deliverable of this epic. Task 4. and 5. are secondary deliverables of this epic.
* 任务 1,2,3 是本节主要实现目标, 任务 4 和 5 是本节次要实现目标. 

### 5 Requirements
### 5 (根据上述问题而提出的)需求

#### 5.1 Failure Recovery 
#### 5.1 失败恢复

The proposed solution should not break the existig fault recovery of Spark Streaming. 
(基于问题而)提出的问题解决方法不应打破 Spark Streaming 中原有的错误恢复机制.

However, Spark Streaming's recovery proceeds from created blocks only - and is not currently idempotent. 
但, Spark Streaming 的错误恢复机制仅会在已创建的数据块上生效, - 并且就目前来看恢复操作并不是幂等.

By only changing block creation, this proposal does not change the reproducibility of the stream's data significantly: blocks created in this way will still enjoy the same fault tolerance. 
在我们所提出的解决方案中仅会变动数据块的构建过程并不会对数据流中数据恢复这里有很大的影响：但凡是通过原有方式构建生成的数据块仍会享有原有的数据容错恢复机制. 

Moreover, in a distinct area, it should be noted that the write to the WAL should, when activated, occur before the registeration of a block. 
不止如此,值得一提的是在另一个地方数据的 WAL 日志写入操作应该先于数据块的注册操作被触发.

At the moment, this is ensured by blocking on the HDFS write call. 
这么做是为了确保 HDFS 上数据块的写入过程能够被 WAL 完整记录,以便于失败恢复. 

This constraint will be maintained in the new solution. 
 这种约定俗成的机制将会在我们提出新解决方案中继续使用. 

Finally, spectacular care will be made to explain with the utmost clarity that strategies that involve data loss (dropping, sampling) are lossy. Those will never be the default.
最后, 对牵涉到因数据订阅端丢弃数据,随机抽样这些处理策略而引发的数据丢失的情况我们会超留意且尽全力详尽地对其进行阐述. 并且确保绝对不会有默认情况下不清不楚地调用默认的一种数据处理策略(丢失,随机抽样).

#### 5.2 Performance 
#### 5.2 性能

The asynchronous signaling of back pressure is expected to allow for a high throughput. 
我们预计在采用反压信号量的异步化之后, 系统整体能够高吞吐地运行. 

Nonetheless, this work should test the performance of Spark Streaming to ensuer there are no regressions. 
虽然想是这么想的, 但是我们必须系统测试下加了这个功能之后的 Spark Streaming 的整体性能,以避免因处理不当而进行的回归测试. 

Morever, this work should come with a throughput variation test demostrates its main advantage: Spark Streaming will not crash when flooded with too much data.
不仅仅是简单的进行系统或是压测,我们应该用一个变量来作为描述系统的吞吐量的量化数,通过这个变量数值的高低来作为评判系统吞吐量的好坏,从而可以通过这个变量清晰地表述出在加上相关功能(back-pressure)到系统中后系统的吞吐量是否有明显提升,以及 Spark Streaming 在上游数据量突增的情况下是否还会崩溃退出. 

#### 5.3 Maintainability 
#### 5.3 (系统的可)维护性

This aims for simplicity, in particular, the measure of congestion should occur at the local level, and be understandable. 
出于简单这一初衷考虑的, 特别是阻塞的测量操作应该在本地层级上以易于理解的方式来执行. 

In particular, care will be taken that at the point of generation of the request signals, so that the discipline for generating the number of allowed elements will be as simple as possible.
特别是, 在生成请求信号量的这一时间点需要特别小心对待才行, 只有这样,才能保证数据元素的编号可以以使有规律且简单地被生成. 

### 6 Alternative
### 6 可选方案

* One alternative to Spark Streaming breaking under load would be to introduce rate-limiting in various points of the architecture, in quite the way the solution to [SPARK-1314](http://issues.apache.org/jira/browse/SPARK-1341) did. 
备选方案 1 是通过在架构各个模块中引入限速处理数据来分散负载,这个解决问题的思路与 [SPARK-1314](http://issues.apache.org/jira/browse/SPARK-1341) issue 相同.

* This would introduce more queues to the system, however, and not only would it not guarantee good behavior under load, but it would multiply the number of settings to tune for a stable configuration.  
但这种将更多的队列引入系统的方法,怎么说呢,不仅无法很好地处理负载,而且也会徒劳增加更多的用于调优的配置选项.

* One other alternative would be to implement blocking back-pressure signaling, but this kills performance in low latencies where there is enough resources to deal with the amount of data. 备选方案 2 是通过在生成构建数据块这一步骤来产生反压信号量, 但是这种处理方法会对系统原有的低延迟性造成影响, 特别是当系统中有充足资源足以计算处理数据的时. (也就是说, 本身反压信号量就是为了根据系统资源量和上游数据密度来控制接收的数据密度, 但如果增加功能过于刻意的话, 会出现一种资源充足应对上游数据，但却为了生成反压信号反而造成系统处理数据延迟的情况.)

* Another alternative would be to signal back-pressure based on a measure of memory taken up by elements of the signal, on one hand, and memory available on each relevant executor, on the other hand. 
备选方案 3 一方面可以通过基于测量被信号元素所使用的内存用量来生成反压信号量, 另一方面也可以通过每个参与运算的 executor 上可用的内存量来生成反压信号量.
(总结下: 备选方案 3 中, 其实是想通过量化内存使用量来做为反压的信号量, 也就是通过内存用量的高低来作为衡量系统负载高低的量化指标,通过这一量化指标来作为反压信号量传递给数据接收端, 这样就可以控制数据接收端在负载低是增大数据接收密度, 在负载重的时候降低数据接收密度)

这里对于上述 3 中方案简单整理下吧(有不对的地方,不过会随着理解不断加深回头及时更正), 
方案 1, 增加队列, 不同队列并发处理,以此来希望降低系统的负载,但是,通过[博文:队列不能用来降低系统负载](http://ferd.ca/queues-don-t-fix-overload.html) （这篇文章的见解很独到）说明了, 徒然增加队列对于系统负载降低无益处, 且加了这么多的队列, 会对系统配置项(SparkConf) 中增加很多参数, 调优费劲. 所以方案 1 pass 
方案 2, 在每个时间间隔周期(Spark 的 batch interval 期间)生成 block 的同时根据系统上下环境参数量化生成反压信号量数据, 但是, 本身构建 block 的时候就希望其能被很快的处理计算掉, 但是由于方案 2 提出的在生成 block 的这个阶段, 还增加一步生成信号量, 这就回大大的增加block 构建时间整体的延迟, 就算系统资源充足, 增加反压数据量化这一步就能将平均数据处理速度拉低, 实在得不偿失. 

备选方案 3 通过内存使用量作为评估系统当前负载情况的量化参考指标, 虽然也有很多问题和不确定不准确性, 但是较比 1,2 备选方案可用性大大增加, 所以最终选了 3. 
以及如下是备选方案 3 基于内存用量来评估系统负载,生成反压信号量的缺点:

* the memory available at a given time for for the cache, as a whole, is not useful for this task: a Receiver's executor, when overwhelmed, will crash with free memory left in the cluster's cache taken as a whole. 
* <b>在时间段已知的情况下(这个时间段我觉得就是 block 生成的 batch interval 周期的起始时间点), 将获取缓存空间中内存用量占比的大小可以作为衡量系统内存使用率是可以的, 但是, 当 Receiver 的 executor 因其处理的任务将内存空间打满而崩溃退出而将其占用的缓存空间释放掉这种情况发生的时候, 这种简介的从局部到整体的评估内存使用率的准确度便会受到影响, 因为释放的全部空间会作为未被使用的内存降低整体内存使用率, 但是 task 因 OOM 退出其实是因为系统内存不足, 所以因 OOM 内存资源缺少而崩溃退出释放出的内存空间反而会降低系统的内存使用率, 这种信号量一旦传递给数据接收方, 接收方会接收更多的数据, 从而产生恶性循环.</b>

<p/>
* this measure is complex to implement since memory usage is impacted by more than elements of the signal as they are read initially. This would have to take into account e.g. by transient RDDs created along the processing of along the processing of the elements of a DStream 

* 测量内存使用量的处理方式很复杂因为内存资源会被很多系统中的其他元素影响到,不仅仅是一开始的信号量引起的内存使用率. 比如说这种情况(就是前面提到的情况)， 在 DStream 中随元素处理计算而被临时创建的 RDD 所占用的内存空间便是上述提到的无法直接测量的 '被很多系统中的其他元素'. 

<p/>
* the discipline for measuring congestion in the presence of several DStreams is unclear: a Receiver's Executor is also susceptible to receiving replicated blocks from another DStream. 

* 用于测量阻塞的规则在应用在一些现有的 DStream 上效果也并不准确: 特别当接收并处理数据的 Receiver 的 Executor 接收处理另一个 DStream 冗余备份的数据块的时.
<p/>

* Finally, measuring the memory impact of elements read from the signal is difficult to do precisely - i.e. better than based on well-timed snapshots of available free cache memory.
* 最后， 根据从信号数据源读取的元素,也就是基于现场快照的环境下获取内存空闲率, 这种方法来衡量内存使用量达到精准十分困难. 

<p/>
* However, an advantage of this centralized, memory-accounting approach is that it ensuers there are no OOMs. 
* 纵使有这么多缺点, 这种集中化处理的带来的好处便是, 基于内存用量的评估方法能够确保没有 OOM 的发生. 
<p/>

* With that being said, the situations in which OOM can happen in our approach seem limited enough for us to favor simplicity in the trade-off with precision（7）.
* 这么说吧, 在我们看来, 出现 OOM 问题的情况就那么几种, 所以我们会以牺牲部分准确度来简化计算测量过程, 因为这种简化采集方法后所获取得内存使用率,已足以解决(OOM)问题. 

<p/>
* Finally, there is the solution of communicating back pressure asynchronously, but without enforcing a rule that Publishers do not exceed their allotted permissions -- i.e. relying on time and happenstance to make sure the Receiver does not overflow the buffer of the cluster's collective Executor, for instance by checking that back-pressure has not gone 'red' every 10 seconds. 
* 一番讨论下来, 总算是得到一个异步通信传递反压信号量且不对数据发布方指定相关数据发布规则解决方案了, -- 也就是说, 在周期发生事件和偶然突发事件中, 通过每 10 秒检测反压信号量是否飘红(超出最大上限阈值), 能够保证其接收到数据量不会超出集群中所有 Executor 内存空间的总和. 

<p/>
* The issus is that this solution would not work for any given throughput -- indeed, it can require an arbitrarily large buffer size. 
* 问题是, 这种解决方案在可以获取任意大小缓存空间的系统中, 是无法提升该系统的吞吐量的.  
<p/>

### 7 Implementation Details 
### 7 （技术）实现细节

* Reactive Streams are transport-agnostic, meaning that the messages between Publisher and Subscriber(in paricular, between the JobScheduler on the Driver, and the Receiver, on an Executor) do no have a transport specified. 
* Reactive Stream 是数据属于传输不可知的, 就是说数据发布方和数据订阅方的对消息传输方式是不确定的, 例如数据发布方可以是 Spark Driver 端的 JobScheduler , 而数据接收方是 Executor 的 Receiver. （观点: 这个地方我对数据传输不可知的理解是: Publisher Receiver 二者之间传输数据的方式 pull 和 push 方式是根据 Publisher 与 Subscriber 二者之间收发数据的速率来决定的）
<p/>
 
* We will resure actors, as implemented throughout Spark Streaming's control plane, to deliver those. 
* 在实现方面, 我们会复用 actor 这一贯穿 Spark Stream 消息发送框架来作为分发数据消息的载体.  
<p/>

* In the case above, that means one more message from the ReceiverTracker (running in the JobScheduler) to the ReceiverSupervisor ( within which the Receiver and BlockGenerator run). 
* 在上述讨论的 Driver 是数据发送方, 而 Executor 的 Receiver 是数据接收方这种场景下, 其实是指消息在 JobScheduler 模块中的 ReceiverTracker 发送到 ReciverSupervisor 中(在 ReceiverSupervisor 中运行了 Reciver 和 BlockGenerator 这两个对象实例).
<p/>

* The implementation of the request number issued by a JobScheduler will match the number of elements cleared (processed) during a batch interval. 
* 在我们的实现方案中, JobScheduler 请求的消息条数将会与每个 batch 时间周期中处理/清空的数据消息条数匹配上. 
<p/>

* In case the job queue is measured to be back-logged, every job will signal its scheduling delay at batch completion. 
* 在将 job 队列中的信息进行量化并将量化值作为返回记录的反压信号量汇总日志, 而对于 job 队列中的每个 job 而言, 每个job 会将 batch 结束后它的调度延迟时间作为其(反压)信号量. 
<p/>

* So, we will remove from elementes processed within the last batch a proportional, configurable fraction of the number of elements that could have been processed during that scheduling delay. 
* 所以,我们将会从最后一个处理批次中移除一定比例的待计算元素,以此来调控延迟调度期间调整待处理计算元素的数量. 
<p/>

* This will serve, in practice, as an integral part of control exerted by a [PID controller](https://en.wikipedia.org/wiki/PID_controller).
* <b> 上述这种调控方法源于 [PID 控制](https://en.wikipedia.org/wiki/PID_controller) 一文中的思想,这种调控方法在实际生产环境中十分有用. </b>


* The BlockGenerator, recipient of the back-pressure signal will then set the adjustment variable:
* <b> BlockGenerator(数据块生成模块), 反压信号量接受者, 在接收到反压信号量之后会根据反压信号数值来调整如下变量数值:</b> 

<p/>

* the maximal number of elements taken as input in every block to match the speed (elements/time) communicated by the request: a well-computed number of elements processed within the lastbatch interval. 

* <b>加载到数据块中的元素上限需要和通信端发送的请求信息: 当前系统数据处理速率(速率: 单位时间内处理元素的数量) 相匹配才行, 而来自通信端的请求信息中记录了在上一个批处理时间段内被系统正确处理的元素数量.</b>

* <b>小小总结梳理一下:</b> 上面这句话包含的这样几个信息, BlockGenerator 负责在单位 batch 时间周期内接收上游数据并构建 block/数据块, 数据块传输给计算模块进行处理计算. 我们将上一个批次 block 中的数据处理元素数量获取到, 计算之后便会得到 总元素数量/计算处理时间 = 单位时间内处理数据元素的数据量, 将这个数据量作为反压信号从计算模块反馈给 BlockGenerator 模块, BlockGenerator 会根据自己 batch 时间周期 * 单位时间处理元素数据量 所得到的这个数值, 作为本次打包成 block 的元素数量. 以及反压 按照中文字面意思来理解, 应该是 '用以反馈（系统）压力(至数据接收段)的信号量'. 

<p/>
 
* A particular point of note is that the back-pressure signal extrapolates the measure of the number of elements processed during a batch interval from asynchronously-transmitted values, sampled at every batch interval clock tick: 

* <b>值得一提的是, 借助于时钟周期抽样获取的反压信号量的数值可以推算出 每个批处理时间段内来自于每个批处理异步广播数值中被处理计算的元素数量</b>
<p/>

* 1. The JobScheduler measures cleared elements in the last batch interval by a precise computation that is sampled at, but not dicated by batch and block interval ticks: if a job starts at batch interval n, and finishes by the end of batch interval n+2 -- i.e. a slow job , a scenario frequent in congestion -- then the number of processed elements in the last batch interval is half the number of elements in the job. 
* <b>1. Job Scheduler 精确计算测量上一批次中被清理元素数据数目是通过随机抽样而非逐一批次逐一数据块时钟周期计算得到:如果一个 job 起始时间为第 n 个批次, 并在地 n+2 个批次后完成 --i.e. 也就是说, 在一个拥塞发生频繁的应用场景中, 运行一个计算缓慢的 job --(这里就是说 n 批次开始 n+2 批次结束的这个 job 对应当前 spark 系统而言是处理特别慢的, 正常速率的 job 通常是 n 批次开始之后就结束, 而这个 job 占了足足 2 个 batch, 会导致后续 batch 延迟, 以及拥塞的发生), --在这种 2 个批次处理完 1 个 job 全量数据的情况下, 最后批次中处理的数据总量其实是当前 job 数据量的一半. </b>

* 2. If the JobScheduler measures that the elements processed in the last batch have been cleared in less than the batch interval, it reports the number of elements that the system would have processed if it had been churning during the whole batch interval. I.e. if a job is the only one executed, starts at batch interval n, and finishes after half of the batch interval is elapsed - i.e. a fast job - then the number of processed elements in the batch interval is twice the number of elements in the job. 

* 2. <b>如果 JobScheduler 检测到上一个处理批次中所处理的元素数量要比每个批次中加载进来的元素数量要少(说明系统当前计算处理数据量跟不上上游到达数据量), 如果在整个批处理时间期间, JobScheduler 中的参加计算的元素会发生流动的话 (这个地方我理解应该是上下批次一直有数据到来, 每个批次都有数据不间断到来, 所以这个批次所生成的反馈信号量会对下一个批次切分数据量的多少造成影响), 那么 JobSchduler 将会把系统能够应付的来的元素数量作为反压信号量发送出去,以此来控制上游接收数据量. 也就是说, 如果当前系统中处于活跃状态的 job 只有一个(只有一个 job 用于计算), 该 job 起始于第 n 个批次,用了半个处理批次时间 job 执行完成, (相当于在 1 个 batch 时间段计算就结束了)也就是说, 这种属于处理快速的 job (通常规定的是 1 个 batch 生成的 job, 应该在 1 个batch Interval 时间段内被处理完成, 前面那个 job 用了 2 个 batch interval 属于慢 job, 而这个 job 用了 0.5 个 batch interval ， 属于快 job ， 这个说明系统当前的处理数据量要快于上游数据到达量，资源充足) -- 在JobScheduler 通过反压信号量将'系统资源充足足以应对更多数据'的信号反馈给 BlockGenerator 之后, 在紧接着下个处理批次中放入到系统中供 job 处理的数据量将会是该批数据量的 2倍.  </b>
<p/>

* 3. In case the number of elements processed during the last batch interval in none, because the blocks processed so far have been empty, then the request will communicate an Int.MaxValue, which amounts to not restricting the amount of elements per block. 

* 3. <b>如果是上一个处理批次元素数量是空的情况(也就是数据断流,上个批次没有上游数据流入系统),由于处理的数据块一直都是空的,所以系统请求数据流元素的数量会被设定为整型最大数值, 也就是数据块 block 中所接收的数据量不封顶,有多少来多少都封到 1 个 block 中.</b> 

* This also implies:
* <b>这同样意味着: </b>
<p/>

* 文章从这里便开始讨论代码中的相关细节了,前面多是逻辑层面的讨论, 后续可以看到 DStream 等相关 Spark 源码中相关的对象信息

* 1) That during a batch interval, the processing times are (locally) linear in the number of elements. Indeed, in the computation above, we assume that we can approximate the processing speed for a single batch interval from processing times that are either slightly larger (slow jobs, taking more than a single batch interval to complete) or slightly smaller (fast jobs, taking less than that a batch interval). This assumption breaks down, of course, for jobs that are non-linear. 
* 1) 在批处理计算期间, 可以认为本地处理计算时间开销和该批次处理的元素数量成线性关系. (之所以强调本地,我估计是为了避免讨论网络传输数据所引起的这一部分时间开销会加到数据计算开销这里). 事实上, 在对计算过程进行描述的过程中, 我们可以通过单个批次的数据计算时间无论是较大的处理时间（当处理的 job 属于慢 job 1 个处理批次/batch没法结束完成）,还是稍小的处理时间(当处理的是快 job 的时候，单个处理批次中 job 计算完成绰绰有余), 来估算出(系统)的处理速度. 如果将这种计算时间开销和处理速度元素数量呈线性关系的假设从宏观层面细化粒度到 job 处理层面的话, 当然了你就会发现, 对于 job 而言, 其处理数据的时间开销和数据量并不一定呈线性关系.

* 2) That onNext(), assuming the BlockGenerator subscribes to the JobScheduler's information, does not push an element of type T down the DStream. Rather, it makes the BlockGenerator produce custom-sized blocks at the set block interval. It is the maximal number of elements in this block that is constrained and answers the back-pressure signal. 
* 2） 还有就是 onNext() 这个被认作是 BlockGenerator 作为反压数据信号量的订阅从 JobScheduler 中所订阅反压消息并不会向数据类型为 T 的 DStream 方推送 数据元素. （这个地方是这样的: 大概作者是怕读者蒙圈, 再次强调了下, 反压数据信号量传递给 BlockGenerator 是用来让它调控 block 数据块大小的参数信息, 而并非是将反压信号量传递给 BlockGenerator 让 Generator 把信号量压成 block 发送给 DStream 参加计算的, 一个是控制信号量,另一个是上游待计算的数据流, 二者是不同的）而是这样, 发压数据信号量在传递给 BlockGenerator 之后, 该信号量会调控 BlockGenerator 在生成数据块时间间隔内调控生成 block 数据块包含数据元素量的大小. 而这个数据元素量的大小是每个 block 数据块中所包含元素数据量最大上限的阈值(也就是最多不能超过这个值,但是比这个值小时完全可以的,不要求完全达到最大上限), 并以此来作为对接收到的反压信号量的响应. 
 
* 3) That the parameter type of Subscriber[T] and Publisher[T] is relatively straightforward: the type T is the type of elements of the DStream, and that it is simply meant to be passed to (and checked by) block generation.
* 3) 那个 Subscriber[T] 和 Publisher[T] 中的参数类型 T 的定义方式是显而易见的, 就是DStream 数据流中处理数据元素对象的类型, 而这个类型也是经由 block generation 模块传递并检查的参数类型. （DStream 的 T 和 Publisher , Subscriber 以及 BlockerGeneration 处理的数据类型全部统一使用 T 这个泛型类型来进行描述）

* 4) That the working of the whole feedback loop depends on little else than a request(n:Int) message - satisfying the locality condition mentioned in [§5.3](https://docs.google.com/document/d/1ZhiP_yBHcbjifz8nJEyPJpHqxB1FT6s8-Zk7sAfayQw/edit#heading=h.26in1rg). The JobSchduler measures how many elements have been cleared from the queue during the last batch Interval, and sends back that information to the BlockGenerator. The limit number of elements for the next block is then set at block generation, after application of the strategy chosen for input limitation ([§4.4](https://docs.google.com/document/d/1ZhiP_yBHcbjifz8nJEyPJpHqxB1FT6s8-Zk7sAfayQw/edit#heading=h.4d34og8), 3.).

 
* 4) 反馈系统当前处理数据压力的(反压)信号量所在的整个反馈闭环工作流的信息传递中除了调用 request(n:Int) 函数外其实也有借助其他方法 - 第[§5.3](https://docs.google.com/document/d/1ZhiP_yBHcbjifz8nJEyPJpHqxB1FT6s8-Zk7sAfayQw/edit#heading=h.26in1rg). 小节中所提到的信号量也需结合本地的状态环境信息等待才可以(也就是消息传递的决策方案需要结合接收消息对象所处的本地情况进行综合考虑才得出最终执行的决策). JobScheduler 在反馈消息流程中负责测量出上个批次计算中共多少元素从队列中移除, 并将这个作为反馈数据信息发送给 BlockGenerator. 这样一来,在 spark 算子对上游下个 block 数据块中的元素数量将会收到限制  ([§4.4](https://docs.google.com/document/d/1ZhiP_yBHcbjifz8nJEyPJpHqxB1FT6s8-Zk7sAfayQw/edit#heading=h.4d34og8), 3.).

  
* 5）When several DStreams are invoked, there are an equal number of independent back-pressure signals involoved. 
* 5) 当 DStream 根据上游数据流而构建多个实例的时候, 系统中会为每个 DStream 构建一个与其一一对应的反压信号量收发模块.

### 8 Concrete code changes 
### 8 (增加反压机制特性后)牵涉到的代码变动

* Our planned transmission pipeline reuses the existing actor transport from the ReceiverTracker to the ReceiverSupervisor (e.g. look at the existing RegisterReceiver message), only adding one message (Request) in the other direction. 
* <b>我们计划复用系统中已有的 ReceiverTracker 与 ReceiverSupervisor 二者之间通信所使用的 actor 消息传输模块来作为传输管道(例如, 你可以参考下 RegisterReceiver 所处理(收发)的消息字段格式), 我们只在消息模块中的发送端增加了请求/Request 新的消息结构.</b>
 
* Our Publisher is a member of JobScheduler. and a class that extends StreamingListener && collects signals from onBatchCompleted to know when to tally processing times (using the helpers of JobSet). Another alternative - probably cleaner, but lowere level - is to register that class to the JobScheduler's listenerBus. 
* 在我们的升级方案中，将反压信号量的数据发布模块以成员变量的方式加入到 JobScheduler 中, 通过继承 StreamingListener 这个流监听基类并且通过重写基类中 onBatchCompleted 此方法来实现记录计算阶段的起始时间（借助于 JobSet 中提供的信息）```JobSet(time: Time, jobs: Seq[Job])```，并在起始时间段内收集反压信号量. 而作为这个升级方案的另一个备选方案或许更容易理解,但变动也更加底层 - 直接将实现的类到 JobScheduler 的 listenerBus 中进行注册,来完成类似功能. 

* Finally, the number of elements should (instead of running a count job) be found in ReceivedBlockInfo's numRecords. Beware of PRs [#5533](https://github.com/apache/spark/pull/5533) and [#5680](https://github.com/apache/spark/pull/5680) that are likely to change that shortly. 

* 所记录下的元素的数量最终可在ReceivedBlockInfo 类中的 numRecords 这一成员变量中查询到(而不是运行一个记录的后台作业来进行元素数量的统计). 详细变动请关注 PRs [#5533](https://github.com/apache/spark/pull/5533) 和 [#5680](https://github.com/apache/spark/pull/5680) 这两个提交代码,近期内有可能会有变动. 


### API Design for congestion management stategies 
### 阻塞管控策略模块的 API 设计

* The intended API is the following:
* (在我们的设计方案中)意图实现如下这样的 API

```
/**
 Called on every batch interval with the estimated maximum number of elements per block that can be processed in a batch interval, based on the processing speed observed over the last batch. 
*/
/**
 这个 onBlockBoundUpdate 方法每个 batch 批次中都会被调用,其通过计算观察到的上个批次中元素被处理的速度, 来估算出每个 block 在每个 batch 批次中所能够容纳的最大元素数量, 
*/
def onBlockBoundUpdate(bound:Int)

/**
Given data buffers intended for a block, and for the following block mutates those buffers to an amount appropriate with respect to the back-pressre information provided through `onBlockBoundUpdate`.
*/
/**
这个 restrictCurrentBuffer 方法被当前数据块所调用后会将调用 `onBlockBoundUpdate` 方法来获取的反压信号量数值作为参考, 根据更新后的数据块需要内存空间大小来从缓冲空间中进行调整.
就是为当前的 block 因为反压信号量更新后, 空间大小有变动, 从总的内存空间(Buffer) 中更新当前 block 缓存空间的大小, 申请新空间的话不能让 block 使用占用全部的内存缓冲区的空间, 如果是 block 变小的话, 或许会将自身的部分缓冲区回归给总的内存空间，如果真有归还的话也会涉及到原有空间的 Buffer 整理相关. 例如 Buffer.append(归还的空间)等等.    
*/
def restrictCurrentBuffer(currentBuffer:ArrayBuffer[Any], nextBuffer:ArrayBuffer[Any])
```

* The implementation involves calling restrictCurrentBuffer during the execution of BlockGenerator.updateCurrentBuffer ( and alas exposes its type).
* 实现中大概是, 在 BlockGenerator.updateCurrentBuffer 方法调用期间,每次调用都会根据反压信号量来调整内存缓冲区大小数值, 在数值更新后通过调用 restrictCurrentBuffer 这个方法来实际执行从缓冲区中划出一部分内存空间来存放 block 数据. 


* The choice of congestion strategy (among existing implementations) would be an optinal configuration parameter of a DStream. 
* 而上述的阻塞策略是否开启 (在将该功能加入到系统中之后) 可以通过对外设置一个 SparkConf 的配置项, 并在 DStream 中检查该配置参数来决定是否开启应对阻塞的相关处理策略.




