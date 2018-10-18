* 说明, 任何文章可通过 [链接](https://github.com/Kylin1027/streaming-readings/blob/master/README.md) + 手动搜索论文名称来查询到下载链接地址


##Semantics and Evaluation Techniques for Window Aggregates in Data Streams

##数据流中基于窗口聚合的语义与估值方法

###Abstract 

###摘要

A windowed query operator breaks a data stream into possibly overlapping subsets of data and computes results over each. 
基于窗口查询操作会将数据流尽可能地打散成彼此叠加的数据子集,然后依次计算出每个数据子集的局部解.

Many stream systems can evaluate window aggregate queries.
许多流系统能够对基于窗口操作执行的查询进行估值(时间和资源消耗的估算).

However, current systems suffer from a lack of an explicit definition of window semantics.
然而, 目前的流式系统中缺少对窗口语义明确的定义.

As a result, their implementations unnecessarily confuse window definition with physical stream properties. 
以至于, 这些流系统的在实现方面 不可避免地 将 窗口的定义 与物理层面的流属性进行混淆.
正因为缺少对窗口语义概念明确的定义, 流系统在其实现中无可避免地将窗口语义的实现与物理层面的流属性二者相混淆.

This confusion complicates the stream system, and even worse, can hurt performance both in terms of memory usage and
execution time.
这种混淆会增大流系统实现的复杂度, 甚至会对系统的性能上带来很坏的影响,例如增大内存的使用率和延长运行时间.

To address this problem, we propose a framework for defining window semantic, which can be used to express almost all types
of windows of which we are aware, and which is easily extensible to other types of windows that may occur in the future.
为了解决这个问题, 我们提出了一整套理论体系来定义窗口语义, 基于这套理论中给出的窗口语义可以应用在我们已知的所有类型的窗口中, 
不仅如此, 改套理论体系同样也能容易地扩展至未来我们可能会遇到的其他类型的窗口语义描述中。


Based on this definition, we explore a one-pass query evaluation strategy, the Window-ID (WID) approach, for various types of window
aggregate queries. 
基于这种窗口的定义, 我们研究出了一次性对查询语句执行代价估值的策略, 我们将其称为是 Window-ID 方法, 简称为 WID 方法, 基于这种方法
我们能够对任何一种基于窗口聚合的查询进行代价估计.

WID significantly reduces both required memory space and execution time for the large class of window definitions. 
WID 这种方法能够显著地降低基于各种类型的窗口在查询过程中所需的内存使用量以及降低查询所需的时间.

In addition, WID can leverage punctuations to gracefully handle disorder. 
不仅如此, WID 还能够借助于标注方法来优雅地处理流中数据无序的问题.


Our experimental study shows that WID has better execution-time performance than existing window aggregate query evaluation-time 
performance than existing window aggregate query evaluation options that retain and reprocess tuples, and has better latency-accuracy
tradeoff performance for disordered input streams compared to using a fixed delay for disorder handling. 
我们的研究证明: 在对窗口聚合查询的执行时间评估方面, 使用 WID 方法要优于已有通过保留数据流中的元组并通过重新执行来评估执行时间的方法,
, 而对于处理乱序的数据而言, 先比较于现有通过固定延迟来处理无需数据, WID 方法有着更精确的延迟处理方法(这里并不是很理解 latency-accuracy tradeoff 是什么意思,
导致这句话翻译的并不是很清楚)。

### 1. INTRODUCTION
### 1. 简介

Many types of data presnt themselves in stream format: environmental sensor readings, network monitoring data, telephone call records, traffic sensor data 
and auction bids, to name a few.
许多类型的数据均是以流的形式存在的, 列如, 采集生态环境数据的传感器所采集到的数据, 网络监视数据, 电话呼叫期间生成的记录, 流量传感器采集到的数据和拍卖过程中持续产生的竞标数据
这些都以数据流的方式存在.

For applications monitoring and processing streams, window aggregates are an important query capacity. 
对于用来监控和处理流数据的应用, 基于窗口的聚合操作是该应用需要具备的重要查询能力。


A window specifies a moving view that decomposes the stream into (possibly overlapping) subsets that we call window extents, 
and computes a result over each.
窗口定义了一个这样的移动视图: 流数据会被分割成(或者是彼此叠加的)数据子集, 而这个数据子集便称作 '窗口范围'.

(Think of a window specification as a "cookie cutter" and window extents as cookies cut with it.)
可以将窗口定义当作是一个切割甜饼的模具, 而由这个甜饼模具切割得到的甜饼便是窗口实体.(即,窗口定义仅仅为描述, 而窗口则是数据流中符合该窗口描述的数据流子集实体)

For example, "compute the number of vehicles on I-95 between milepost 205 and milepost 245 over the past 10 minutes; udpate the count every 1 minute" 
is a window aggregate query where successive window extends overlap by 9 minutes.
举个例子, 让你"计算出在过去的 10 分钟内位于 I95(公路上 真不知道 I95 是个啥) 205 至 245 英里范围内经过的机动车辆的数目; 然后按照每 1 分钟的频率更新统计出的数目" 
这个应用场景便是基于窗口的聚合查询操作, 在这个聚合查询中查询结果通过基于连续的窗口以 9 分钟为时间单位叠加查询统计得到.


Evaluation window aggregate queries over streams is non-trivial.
置于数据流上的窗口聚合查询代价估算是十分有用的(后面的几行文字就是对估值的作用进行介绍说明).

The potential for high data arrival rates, and huge data volumes, along with near real-time requirements in many stream applications make memory and 
execution critical. 
对于许多流处理应用系统来说, 其处理数据到达的速率, 处理数据量级,以及对计算结果接近实时输出的要求对这些流计算系统提出了许多内存和运行时间等严格的要求.

Bursty and our-of-order data arrival raises problems with detecting the boundaries of window extends.
通过探知窗口范围可达的边界能够用来应对上游数据流的激增和数据无序到达引发的问题 .

Out-of-order data arrival also complicates the process of determining the content of window extents and can lead to in accurate aggregate results 
or high latency in the output of the results. 
乱序倒到达的数据同样加剧了对窗口范围界限判定的难度,并且会导致聚合结果在准确度或是计算结果的延迟生成与输出
(流计算中计算结果延迟输出会导致环环相扣的下游窗口聚合计算结果的计算精度受损或是出错,是比较严重的问题)

We have observed that accommodating out-of-order data arrival can introduce much complexity into window query evaluation.
我们观察到对乱序到达数据处理的不当会大大增大窗口查询估值的复杂度.


We see two major issues with current stream query systems that process window queries.
我们已经了解过当前流查询系统中在处理窗口查询方面所面临的两个主要的问题.

One is the lack of explicit window semantics.
其中之一便是缺少对窗口语义明确的定义.

As a result, the exact content of each window extents to be confused with window operator implementation and physical stream properties. 
正式由于缺少窗口语义明确的定义, 导致了每个窗口中的具体内容和置于该窗口的操作在实现中应该将窗口作为逻辑概念还是物理层级来处理这二者引发了一系列混淆.
(这个问题后续的有篇论文中也有介绍过, 就是因为窗口这里的定义引发的混淆导致流系统在实现过程中对流数据处理在物理和逻辑层面的划分存在了很大的问题,
也正是低水位概念提出的原因)

The other is implementation efficiency , in particular, memory usage and evalution time. 
另一个便是实现后执行起来的效率问题, 特别是在内存和估值时间这两个地方。

To evaluate sliding window aggregate queries where consecutive window extends overlap (i.e. each tuple belongs to multiple window extends),
most current proposals for window queries keep all active input tuples in an in-memory buffer.
为了当对连续且叠加(同一个元组位于多个窗口范围内)的滑动窗口聚合查询估值时,目前最常用的方法便是将所有处于活跃状态的上游输入数据保存在内存缓冲区中
(未知上限的内存开销,内存开销过大不说也容易引起OOM)

In addition, each tuple is reprocessed multiple times -- once for each window extent to which it belongs. 
不仅如此(即抛开内存开销巨大不说, 同一个元组因为窗口叠加导致重复计算也是个问题), 每个元组都被重复计算多次, 每次其所在的窗口范围进行计算这个元组都会被重新计算.


We will propose an approach that avoids intra-operator buffering and tuple re-processing. 
在我们提出的方法中 (其实就是这个 WID 咯), 避免了内部操作引发的缓冲区消耗,以及相同元组重复处理这两个问题.


In this paper, we present a framework for defining window semantics and a window query evaluation techinique based on it.
在本篇论文中, 我们提出了用于定义窗口语义的一套理论体系,和用于对窗口上执行的查询进行估值的方法.


In the framework, we define window semantics explicitly -- independent of any algorithm for evaluating window queries.
在这套理论中, 我们给出了窗口明确清晰的定义, 该窗口语义定义独立于任何一种对窗口查询的估值算法.

From our definition, it is clear that many commonly used types of windows do not depend on physical stream order. 
基于我们的对窗口的定义, 大多数目前使用的窗口实现无需再依赖于物理视图上数据流的顺序.(我们的窗口为逻辑视图层级和物理层级分开)

However, most existing window query evaluation techiniques assume that stream data are ordered or are ordered within some bound. 
然而, 现存的窗口查询估值方法中都是假定窗口中处理的流数据是满足序列的或是至少在界限内是满足局部有序的。

Our window query evaluation techinique, called th Window-ID approach (WID), is suggested by the semantic framework. 
而我们的窗口查询估值方法, 我们称之为 Window-ID 方法, 即, WID, 十分适用于当前的语义理论体系中.

Our techinique processes each input tuple on the fly as it arrivs, without keeping tuples in buffers and without reprocessing tuples. 
我们的处理方法是对每个输入元组依次进行处理, 不会将处理的元组保留在缓冲区中且不会对同一元组进行重复处理.

Our experimental study shows significantly improved execution-time performance over the existing evaluation techiniques that buffer and reprocess tuples. 
我们的实验研究数据表明, 先用窗口估值方法中将元组缓存并且对元组进行重复处理严重增大了执行时间和性能上的开销.

 
In constrast to other techiniques, another feature of WID is that it can process out-of-order tuples on the fly as they arrive without 
sorting them into the "correct" order.
不同于现有的窗口查询估值方法, WID 估值方法的另一个特点便是它能够在数据传输的过程中就对数据进行处理, 这样当数据到达的时候
无需对数据进行排序就能将其按照"正确" 的顺序进行存放.

It does not require a specific type of assumption about the physical order of data in the stream.
然而实现这种处理无需的方法并不需要让物理层级上的数据流也满足顺序到达的要求.


Instead, it use punctuation to encode whatever kind of ordering information if available.
取而代之的是, WID 通过标注方法来对数据流中的任意能获取到的属性进行排序.

In the later part of the paper, we examine real-life examples of stream disorder and discuss disorder-handling methods.
在本篇文章的后续部分中, 我们将会审视现实生活中的无序数据流的事例, 然后讨论解决无序数据流的解决方法.

Slack and hearbeats are mechanisms proposed for handling disorder in input streams.
松弛法和心跳法都是目前用作解决输入数据流中数据乱序的解决方法。

Different means for handling disorder can affect the flexibility, scalability and performance of window query evaluation approaches.
采用哪种方法来处理乱序数据会对窗口查询估值方法的灵活性,扩展性以及执行起来的性能有很大的影响

We experimentally evaluated latency-accuracy tradeoffs for handling disorder using WID and using sort-based slack.
在我们的实验中, 我们使用 WID 和 基于排序的松弛方法来对处理乱序数据所带来的延迟精确时间进行比对. 
(latency-accuracy tradeoffs 这几个词在文中同时出现大概 2 次, 应该是用来描述 处理乱序数据时 权衡延迟和数据准确性所做权衡时 所花费的代价估计)


This paper is organized as follows: Section 2 provides a running example that illustrats the basic concepts of WID;
本文内容组织如下: 第2部分中介绍一个可运行实例, 通过对这个案例的讲述介绍了 WID 中的基本概念.

Section 3 introduces our framwork for defining window semantics;
第 3 部分介绍了我们用来定义窗口语义的理论体系.

Section 4 presents WID;
第 4 部分介绍 WID 这个对窗口查询操作进行估值的方法.

Section 5 analyzes disorder using network flow data and discuesses mechanisms for handling disorder ; 
第 5 部分分析了网络数据流中数据乱序的不同处理方法

Section 6 presents performance results; 
第 6 部分展示了不同方法的比对结果

Section 7 discusses the extensibility of our work and Section 8 concludes.
第 7 部分讨论了对我们工作后续的研究方向，第 8 节中我们给出研究结论.


2. RUNNING EXAMPLE 
2. 运行实例
We introduce a running example that illustrates the operations used in WID.
我们将介绍一个可运行的示例, 借助该示例来对 WID 方法中用到的各种操作进行讲述.

Through this example, we show that with WIDs
通过这个案例会让你知道,使用 WID 方法的话:

1) there is not need to retain input tuples in buffers, although there many be queues to pass tuples between steps; 
在使用 WID 的时候无需将上游输入的数据元组存放在缓冲区中, 虽然在不同的步骤中我们会通过队列的方式来进行元组的传递.

2) each tuple is processed only once at a given operation step; and 
每个元组在其所在的窗口执行操作步骤时能保证全局范围内这个元组仅会被处理一次.

3) no assumptions about the physical order of the input are required. 
处理输入数据流时无需借助于物理层级的数据流顺序来推知当前逻辑层面的数据流的顺序情况。


Consider a radiation detection system that can be installed along freeways, such as the one under study in the New Jersey
Turnpike Radiation Detection project at Lawrence Livermore National Lab. 
设想有这样一种探测系统, 它安放于公路边上, 就像是那个啥地方的什么实验室的探测器.

A radiation detection system identifies potentially dangerous vehicles, tracks them as they progress along the freeway,
and targets a vehicle confirmd to have radioactive material for an interception. 
探测系统能够识别潜在的危险车辆, 并在这些车辆在公路上行驶时对其进行追踪, 不仅如此在检测到某台汽车上携带放射性物质后还会对其进行拦截.

Figure 1 shows four detection stations involved in a detection task on I-95 northbound from I-195 to the Holland Tunnel. 
图 1 中展示的是安放的四个探测装置参与一个从 I-195 Holland 隧道向 I-95 北行驶的探测任务. 
While tracking vehicls, it is critical to accurately forecast travel time between detection stations, so that the system does not lose track of suspicious vehicles. 
当追踪车辆的时候, 是否能够精准地播报位于两个探测装置间车辆的运行时长有着重大的意义,以至于系统不会跟丢它探测到的可以车辆.
(大概是对两个探测器之间的交替探测转接时机有决定性影响)

A freeway is separated into non-overlapping segments by adjacent ramps. 
公路被相连的坡道分离成多个彼此不交叠的路段.


Suppose that there exists a speed sensor (such as a pair of inductive loop detectors commonly found near freeway on-ramps) 
per segment along the freeway, and that speed readings are streamed to a central system, where the min and max speed for each segment of the 
freeway over the past five minutes are computed, and updated periodically. 
假设每个公路的路段上安置有速度探测器( 例如像我们在公路扑倒上常见的那种成对出现的感应线圈<然后靠切割磁感线产生电流啥的探测速度emmm 可还行>), 
在探测到机车行驶的速度之后将数据流回传到中心系统中, 在中心系统中每 5 分钟会对采集到的机车在每个路段上最大,最小行驶速度进行计算, 更新计算结果.

Then, min and max travel time between stations can be calcuated easily an continuously updated based on the current speed bound for each segment
and the length of the segment . 
然后,通过当前路段汽车行驶的速度和每个路段的长度, 便可轻易地推算出机车在相邻采集装置安装点间行驶的最大,最小时间,并且计算结果会持续地刷新.

Suppose the schema of speed sensor readings: <seg-id, speed, ts>, where seg-id is the segment id and ts is the timestamp for a sensor reading.
假设速度探测器读取到的数据模板是这样的 <seg-id, speed, ts>, 其中 seg-id 是每个路段的 id ，ts 是每个探测器采集到数据时当时的时间戳.

We might choose to consinuously compute min and max speed of each segment by computing the min and max over the past 5 minutes, and updating the results
every minute. 
我们能通过计算过去 5 分钟内最大,最小速度, 然后每分钟刷新一次计算结果来持续地得到汽车在每个路段上最大和最小的行驶速度.

We call this query Q1, shown below in a CQL-like language. 
我们将上述介绍的计算方式用类 CQL 语句来实现, 将其称为 Q1: 
Q1: SELECT seg-id, max(speed), min(speed)
FROM Traffic [RANGE 300 seconds SLIDE 60 seconds WATTER ts]
GROUP BY seg-id

Figure 2 shows the steps that WID uses to process Q1.
图 2 中描述了基于 WID 方法来执行 Q1 查询的详细执行步骤.

The details of the operators used for these steps are given in later sections . 
基于 WID 方法在查询过程中所执行的具体操作我们后续小节会给出详细说明.

The input traffic speed stream, with punctuations, arrives at the query system. 
上游带有标注的速度数据流抵达至查询系统.

Briefly, a punctuation is information embedded in a data stream indicating that no more tuples having certain attribute values
will be seen in the stream. 
简单来说, 数据流的标注是一种嵌入到数据流中的信息, 通过这种嵌入在数据流中的额外信息能够知道数据流中 包含某个属性值的 元组在后续接下来达到的数据流中将不会再有了. 
简单来说, 标注是将(额外)信息嵌入到数据流中的方法, 借助标注嵌入信息的方式能够向流接收者传递这样的信息: 在后续达到的数据流的所有元组中包含某个属性值的元组将不会再出现了.

For example, punctuation p1 indiecates that no more tuples arrive from segment s6 that have a timestamp attribute value less than 12:11:00PM .
例如, 标注 p1 便是用来传递这样的信息: 在接下来由第 6 路段(s6)传递来的数据流中元组中时间戳(ts) 晚于时间 12:11:00PM 的元组将不会再出现.
(这么做是为了表明在执行 xxx-12:10:59 时间段的聚合查询的时候保证所有的采集到的数据都是齐全的,不会有延迟的数据到来无需等待延迟数据到来再开始计算,或许这个大概指的也是
lattency-accuracy 吧, 即因为数据延迟而对计算结果精确性造成的影响)

In our example, we assume that each individual sensor provides such a punctuation every minute.
在我们的这个事例中, 我们设定每个探测器每隔 1 分钟都会发送一个标注字段.

As Figure 2 shows, in the first step, each input tuple is tagged with a range of windows-ids. 
如图 2 所示的一样, 在 WID 方法的第一步, 每个输入元组都被打上了窗口 id 的范围数值来作为其属性字段.

In WID, each window extend is identified by a unique window-id. 
在 WID 方法中, 每个窗口范围都通过唯一的 window-id 来进行标识.

In this example, we use non-negative integers for window-ids.
在我们的这个示例中, 我们使用非负整数作为 window-id

Suppose Q1 starts at 12:00:00PM. 
例如,我们执行 Q1 查询时将初始时间设定为 12:00:00 PM

Each window extent is a 5 minute sub-stream, which overlaps with adjacent window extents.
每个窗口范围是由 5 分钟时间段构成的子数据流(sub tuples), 在这一系列子数据流中相邻的窗口范围均彼此交叠(tuples 同时存在于相邻的两个窗口范围内).

In our case, for example, window extent 10 is the 12:06PM - 12:11:00PM substream, and window extent 11 is the 12:07:00 PM - 12:12:00PM substream.
在我们的这个事例中, 例如我们可以将时间戳位于 [12:06:00PM, 12:11:00PM] 范围内的子数据流构成窗口范围的 window id 设定为 10, 而tuple 的时间戳数值位于
[12:07:00PM, 12:12:00PM] 这个数值范围内的元组构成的子数据流而对应的窗口范围使用 window id 11 来唯一标识(注意在这个应用场景中时间或者是说 tuple
是存在交叠的, 即如果某个 tuple 的字段时间戳是 12:08:00PM 那么这个 tuple 是同时位于两个 窗口范围<window extend> 内的)

For each input tuple, we can calculate the window-ids for the window extends to which it belongs.
在对输入的数据流打上了 WID 标注之后, 我们能获取到每个元组中的 window-id 这个数据字段,从而能够推算出这个元组都属于哪些窗口范围
(注意这里的 window-extends 是复数表明了一个 tuple 因为窗口叠加的缘故有可能属于多个窗口范围)的.

(不得不说每种说明后面都有对上述抽象说明而举的实例说明)
For example, t1 belongs to window extends 10 through 14. A similar calculation is applied to punctuations. 
例如, 图 2 中的 t1 这个元组根据 WID 之后它属于窗口范围 10 到 14. 标注方法中也有着类似的计算法则(这里第一次看的时候每太看懂,是指作用于数据和作用于
标注数据的操作二者之间有相似之处么?)

Each input punctuation, which punctuates on the seg-id and ts attributes, is transformed into a punctuation on the seg-id and wid attributes.
对于每个标注操作而输入的数据元组而言, 在这个标注的过程中会将输入元组中的 seg-id(路段 id) 和 时间戳属性数值转换成 seg-id(路段 id) 和 wid 属性字段数值. 
[在这里大概清楚了, 标注并不仅仅是对元组中的属性字段进行加工二次处理, 而是在二次处理之后创建一个新的元组,这个元组中携带了对之前处理过的属性字段的信息的统计]

For example, p1 is transformed into p1', which indicates that no more tuples from the sensor at segment s6 for window extent 10 will arrive. 
例如, (仍旧是图 2 中) 的 p1 被转换成了 p1', 后续接收数据流进行处理的单元在获取到这个字段之后便可得知来自 s6 路段的窗口范围为 10 的元组将会到达.

Note that we extend the input schema of the speed tuple by adding the wid attribute as an explicit data attribute. 
在这里你需要知道的是, 我们继承了原有速度数据元组字段模型然后显示地在这个元组数据模型中增加了一个属性数据字段.

Also note that in step 1, each tuple or punctuation is processed immediately as it arrives, and is streamed out immediately after processing. 
同样需要了解的是, 在第一步中, 每个元组或是标注都是一到达便被执行了上述处理, 并且处理之后立即输出流向至下一个接收处理单元进行后续处理操作.

The second step is an aggregation step where tuples tagged with window-ids are grouped by the seg-id attribute, as well as the wid attribute.
在接下来的第二执行步骤中是一个聚合操作, 在聚合操作中被打了 window-id 标签且有着相同 seg-id 属性值和 wid 属性值的元组会通过 group by 操作分到同一组中.

Note that a tuple tagged with a range of window ids represnts a set of tuples, each tagged with a single window-id.
这里注意我们所说的一种打了 window id 范围标签的元组所表示的是多个元组对象且每个元组对象只打了一个 window id 标签.

An internal hash table is used to maintain the partial max and min value for each group.
系统内部会维护一个 hash 表，通过该 hash 表来记录每个分组中的局部最大和最小数值.


Upon the arrival of a punctuation, the hash entry that matches the punctuation is output and purged from the hash table. 
在每个到达的标注元组中, 元组标注和 hash 表 key 匹配的标注元组将会被继续传输下去, 并且将 hash 表中匹配的 key 从表中移除.

For example, when punctuation p1' arrives, m1 is output and its corresponding hash entry is cleared. 
例如(仍旧是图 2 中)当 p1' 这个标注元组达到的时候, 经由 hash 表映射过滤 m1 会作为其后续数据输出, 而 p1' 其对应的 hash 表的入口(key) 从表中清除了。


Overall, introducing window-ids into query execution brings benefits to both performance and system implementation.
总之, 将 window-id 引入至查询执行中对查询性能和系统性能都有提升.

It reduces operator buffer space and execution time; and it transforms window aggregate queries into group-by aggregate queries and thus reduces the implementation
complexity of the system.
通过 WID 这种方法能够减少操作缓存空间的开辟与使用和执行时间上的开销; 并且 WID 能将作用在全局无界范围内的数据流上的窗口聚合操作分解为
分组聚合操作，并以此降低了流系统实现的复杂度.


Also observe that it does not need to reorder tuples ts, as long as punctuation are placed correctly. 
我们同样观察到, 只要是加到数据流元组中的标注信息使用得当, 是无需对数据流元组中的时间戳字段进行重排序.

WID does require having calculations for multiple window extents under way concurrently, but the storage overhead is trivial unless 
there are many more window extents than tuples. 
WID 方法确实需要实时地在多个窗口范围内执行计算是需要一定的内存空间的, 不过除非生成的窗口范围本身要比数据流中的元组数目还多，否则的话这点内存开销压根算不上啥.

 

(2018/09/15 更新)

3. WINDOW SEMANTICS 
3. 窗口语义(第三小节主要给出了窗口的定义,在这里理解 WID 的概念是这篇文章的重点)
As can be seen from our example, key to WID is the association of tuples with window-ids.
从我们上述例子中可见, WID 概念的关键之处便是(通过某种方法将) 元组 和 窗口 id 二者相关联起来.（与其说是关联倒不如说是建立二者之间一一映射的关系更贴切，文章看到这里的想法）

In this secion we present a semantic framework that makes this association explicit, independent of any particular operator implementation.
在本节中我们所提出的语义体系独立于任何特殊的操作实现将二者(window-id 和 元组) 之间的关联关系进行阐明.

In Section 4 we return to window-aggregate evaluation based on this semantics.
在第四节再基于第3节给出的明确窗口语义使用在窗口聚合操作的估值上
(即提出了一个抽象的定义后，再将其应用在一般事例上，以此来验证这种抽象概念用到特例上是否正确)

3.1 Motivation 
3.1 (提出窗口语义) 的动机

In previous work, window semantics often has been described operationally.
在前文讲述的相关流操作中, 窗口语义通常被用作一种操作/动作(某种把数据流划分到一个数据子集中的操作)来进行描述.

However, operational window definitions tend to lead to confusion |of the window extent definition with physical data properties and implementation details. 

然而, 这种将窗口定义为一种将数据流切分为数据子集的动作这种做法, 会将读者(以及基于这种定义所实现的流系统的使用者) 引入一种这样的误区: 将用于处理物理层级数据特征的窗口范围定义与其物理层级实现的细节相混淆.

For example, some current window query operators process window extents sequentially -- that is they close the active window 
when a tuple past it arrives, which translates into a requirement that their input arrive in order of the window attribute.

例如, 现在所执行的查询操作会顺序地处理窗口 -- 这是说 在这种处理语义中:当元组过了其所应该到达的截止时间到达后接收其元组的窗口对其是关闭的, (
也就是元组从其被构建到其被窗口中进行处理这段时间是有时限的, 过了窗口的活跃时间窗口关闭不接受后续到来的元组,即便元组中的时间戳属性标明了该元组是应该参与该窗口时间范围内的计算)
该语义在具体实现中被解释成了: 窗口在计算过程需要上游数据中的属性字段按照顺序来处理.

If the data is not in order, some sort mechanism such as Aurora's BSort must be used to reorder the data. 
如果数据并非顺序到达, 一些排序算法操作便不得不增加数据顺序进行重新调整这种操作，Aurora 流系统中的 BSort 这个排序方法的实现便是如此。

Without a mechanism to explicitly identify what extends tuples belong to, tuples cannot be processed in their arrival order ( unless it corresponds to window order), which leands to retaining tuples in the implementation, latency, and inflexibility of query evaluation .
缺少用来明确确定参与计算的元组应该属于哪个窗口范围的方法，元组的处理将无法按照其达到的顺序来进行, (除非在元组和窗口范围二者之间构建一种映射关系, 让每个元组有其所映射的窗口, 从而知道它属于哪个窗口,会参与那些计算),这就造成了现在流系统中的一个通病: 因为不确定元组何时被计算而将元组先进行缓存这种实现, 而导致了流计算的延迟, 估值查询灵活性下降这些问题.


We propose a semantic framework, and define semantics of existing types of window under this framework.
我们提出了一套语义体系, 在这套语义体系中给出了适用于现存窗口语义的通用定义.

Our window semantics definition is indepenent of any implementation algorithm.
并且所提出对窗口语义的定义是通用的独立于任何一种算法实现
(这里作者反复强调,大概是为了说明他们对窗口语义的定义是从 framework 中推论得出的,并不是受了那种特例算法的启发从一般到特殊推出来的)

Having explicit window semantics leads to directly to a flexible implementation that can handle a wide variety of windows 
and which can handle disordered data in a number of ways. 
基于清晰明确的窗口语义的定义对流系统而言实现起来灵活度更高, 能处理很多类型的窗口, 所以用于处理数据乱序问题的方法也随之大大增多.

In addition, an explicit definition makes it easier to verify the correctness of a window operator implementation. 
除此之外, 清晰明确的定义有利于检验基于窗口的操作实现是否符合窗口语义的检验也变得更加方便. (如果窗口语义定义明确, 那么基于窗口而设计实现的不合理操作在开始设计的时候便可根据窗口的特点推测其是否可行是否正确等)

Note that defining window semanitics and implementing the defined semantics are two separate issues. 
应该谨记的是: 窗口语义的定义 和 基于语义定义而实现的不同操作 分别属于逻辑和物理两个层面 是两个分开的不同的问题.

A window semantics definition specifies the content of window extends, while implementation issues, such as determining when to process an extent
(and whether to approximate its actual value), are handled by separate mechanisms or directives. 
窗口语义定义中阐明了 窗口范围 中应该包含的数据流中的哪些内容, 而窗口定义在实现中的问题是, 例如是 决定 执行一个窗口范围 的时机(以及是否对其实际数值进行预估),
这两个 定义层级(逻辑层级) 和 实现层级(物理层级) 通常被划分成解决策略 和 实现之后让计算机执行的指令 这两个分离的问题来考虑. 
（这个句式比较复杂,从我个人理解这样的: 作者先说了 窗口语义定义是做啥的, 然后话锋一转并列地说了 基于语义的实现是啥样的, 而这个 which 并非是定语从句指代
前面句式中的某个对象的, 而是起到转折作用, 依据就是前一句中 window semantic 和 implementing the defined semantics 二者是对立关系, 而在这句中
window semantics 和 implementation(其实就是前一句中的 implementing the defined semantics) 也是对立关系, 
同时, 后一句中的 are handled by separate 也证实了前面说的是两个对象, 并非一个补充说明另一个, 而是对立, 并列的关系, 
以及最后的 mechanisms 这个指代的是逻辑层面策略, 而 directives 是物理层面是计算机指令, 后来发现 while 被我看成了 which emm 可还行）

3.2 Window Specification 
3.2 窗口说明 如果说第 3 节是全文重点,这小段就是第 3 节的核心,然而看完后面回来再看这一小段并不是就是个引子...
窗口说明由 窗口类型 + 窗口参数, 窗口说明不通用, 但是窗口说明.窗口参数 通用 获取后续窗口语义定义是基于或是借鉴了 窗口参数这里)

A window specification is a window type and a set of parameters that defines a window to be used by a query.
窗口说明 由窗口所属类型和执行查询操作中传递的一些列参数组成.

For example, the specification of the sliding window in Q1 has parameters: RANGE, SLIDE and WATTER. 
例如, Q1 中的窗口说明中,其窗口所属类型是: 滑动窗口, 其参数是: 执行 Q1 查询操作中所用到的： RANGE, SLIDE 和 WATTER

In our window semantics, the content of a window extent is determined by applying a window specification to a set of input tuples. 
再回到我们的窗口语义这块, 窗口范围中的内容决定了窗口说明中所描述的查询操作将会应用在 输入数据流中的哪些元组上. 
(也就是, window-extent 窗口范围划分了数据流中元组粒度上参与计算的元组集合, 而窗口说明则是指定了这批元组集合构成哪种类型的窗口, 在这个窗口上执行何种操作, 
一个是划分数据,粒度到元组, 另一个是对划分的元组上执行计算操作)

Our goal of discussing window specification here is to introduce the parameters used to express different windows whose semantics will be defined later, 
but not to provide a universal specification for all possible windows.

我们在这里之所以讨论窗口说明, 是为了引出窗口说明中的窗口参数,因为它能够作为标识不同类型的窗口，这些不同类型的窗口语义的定义稍后给出, 
不是为基于窗口说明来讨论提出一个通用窗口说明适用于现有和今后可能出来的窗口类型


However, our window specification parameters are general enough to express almost all stream window aggregate queries we have seen.
虽然窗口说明使用窗口类型有限,但是窗口类型中的参数却足够地通用以至于基于窗口说明中的参数几乎能够能拿来定义我们目前市面上所见的所有流数据中的窗口聚合查询类型.


Our window specification for sliding window aggregate queries consists of three parameters, RANGE, SLIDE and WATTR, which specify the 
length of the window, the step by which the window moves, and the windowing attribute -- the attribute over which RANGE and SLIDE are specified. 
上面我们说到的窗口说明时所提到的 : 滑动窗口聚合查询操作查询 
将滑动窗口聚合查询作用在前面提到的窗口说明中, 窗口说明.窗口类型 = 滑动窗口, 窗口说明.窗口参数类型 = RANGE, SLIDE, WATTR 其中这三个参数的作用是这样的: 
RANGE: 控制了窗口中容纳的元组个数,也就是控制窗口的大小,其中容纳元组构成的数组/列表/队列的长度
SLIDE: 控制了每次窗口向前滑动的步数,
WATTR: 指明了窗口是针对数据流中的元组中的哪个属性构建的，滑动操作针对数据流.元组.哪个属性来滑动的, 每次滑动的步数是针对数据流.元素.哪个属性而言的,即, RANGE 和 SLIDE 的定义与操作对象是元组中的哪个属性.

就是这样
数据流
... (Attribute-1:1, Attribute-2:2, WATTR:value-1),(Attribute-1:3, Attribute-2:43, WATTR:value-3),(Attribute-1:5, Attribute-2:23, WATTR:value-xx) ....
窗口
...|(key:{WATTR:value-1}, value:{Attribute-1:1, Attribute-2:2, WATTR:value-1}),(key:{WATTR:value-3, value:{...}}),(key:{WATTR:value-xxx}, value:{}) | ...

比如上面的这个窗口, RANGE =3 那么上面的这个窗口中共包含 3 个元组, 针对的属性是 WATTR (当然 RANGE 通常是制定数值范围的, 例如 指定元组中 WATTR 满足 WATTR from value-1 to value-xxx 的所有元组)
如果我将 SLIDE = 1 ， 那么下个窗口中将会抛弃 WATTR:value-1 这个数据, 然后继续读后一个元组(当然, 如果是范围的 RANGE 的话, SLIDE 这里有可能是通过函数的方式来更新 RANGE 这里生成的范围数值)

For ease of presentation, we assume the arrival time and the arrival position of tuples in a stream are explicit attributes arrival-ts and row-num in the input tuples. 
为了方便说明, 我们将数据流中的元组的达到时间与到达位序作为输入流元组中的 arrival-ts (达到时间戳) 和 row-num (列序号).


In the following, we introduce different types of windows and their expressions in window specification. 
在接下来的讲述中, 我们基于窗口说明来引入不同类型的窗口和他们的查询参数表述 .


A time-based sliding window query such as Q1 shown in Section2, is expressed with RANGE = 300 seconds, SLIDE=60 seconds and WATTR = ts. 
基于时间的串口查询, 例如第 2 节中讨论的 Q1 查询语句, 这个语句中的查询参数表述是 RANGE=300 seconds, SLIDE=60 seconds WATTR=ts .


(Note that in this example, ts is the timestamp atrrbite provided by the sensors and not the arrival timestamp)
不过注意这个例子中的 WATTR 对应的 ts 时间戳并非是前面我们说明中的达到时间戳, 而是由传感器在生成数据的时候给数据流中的数据元组打上的时间戳来标志该数据元组生成的时间.

Tuple-based sliding window quereis are also common. 
基于元组执行的滑动窗口查询其实也挺常见的.

A tuple-based query uses the row-num attributes of tuples as the WATTR.

如果滑动步数是以元组自身作为参照的, 那我们就使用 row-num 来作为 WATTR (即, window attribute ).

For example, consider Q2, which asks "Count the number of vehicles for each segment over the past 1000 rows, update that result every 10 rows"
and is expressed as : 
例如, 让我们来回想一下 Q2 这个查询：“每 1000 条元组统计每个路段中机车的数量,然后每 10 条元组数据更新一次统计的数据结果” 的查询语句如下: 
Q2: SELECT seg-id, count(*)
FROM Traffic [RANGE 1000 rows 
SLIDE 10 rows
WATTR row-num]
Potentially, WATTR can be any tuple attribute with a totally ordered domain. 
其实吧，WATTR 这个关键字能用来制定数据流.元组中的任何一个属性, 只要是 RANGE 所指定的窗口范围内这个属性是有序的就行.

Having this option allows us to define windows over timestamps assigned by external data sources or internally by the system; 
to handle a stream with a schema containing multiple timestamp attributes; and to window over non-temporal tuple attributes. 
有了这个 WATTR 这个关键字语义的定义, 让我们做这 3 件事成为了可能
1. 给数据流元组中所打的时间戳这个操作 既可以由生成数据流系统以外的数据流处理系统来，也可以由生成数据流元组的内部系统自身来，然后基于这个打上的时间戳执行窗口聚合查询等相关操作
2. 描述元组数据结构的数据模板中即便有多个时间戳属性字段也能够轻松区分搞定
3. WATTR 还能用来针对元组中的非时间属性字段分布的范围来构建窗口(,并给予构建的窗口来执行聚合查询等操作, 此处突出了 WATTR 面向属性的通用性并非仅仅针对时间戳等时间属性的字段才能构建窗口)

Another kind of sliding window is when the RANGE and SLIDE are specified on different attributes.
另一种滑动窗口类型是这样的: 在这种类型的滑动窗口中 RANGE 和 SLIDE 是由数据流.元组 中的不同属性字段来各自指定的.

In such a case, SATTR(slide attribute) and RATTR (range attribute) are used in place of WATTR to express the attributes over which SLIDE and RANGE 
are specified, repectively. 
在这种场景下, SATTR 这个关键字 (用来描述窗口是相对哪个属性进行滑动的) RATTR 这个关键字 (用来描述属性对应的数值的分布情况) 会替换原有 WATTR 这个关键字, 通过指定 SATTR 和 RATTR 便可以各自清晰地描述出 1. 窗口范围的划分是通过元组中的哪个关键字来制定的(SATTR = attribute of the slide), 2. 窗口向后滑动的列数(row-num) 是相对于哪个属性而言的(RATTR attribute of the range).
(其实就是针对 RANGE 和 SLIDE 操作属性字段进行了更细致的语义拆分)

A common exmaple of this type of query is a query with RANGE over a timestamp (ts, in our example) attribute (RATTR) and SLIDE 1 row over row-num (SATTR).
上述抽象描述最常见的案例是这样的查询: 查询语句中通过时间戳(在我们的案例中是 ts 这个属性字段) 这个数据流.元组.属性字段作为切分窗口范围的属性字段, 即 RANGE 中描述的数据范围是
来自于 RATTR 这个关键字所指定的(数据流元组中的)属性, 而 SLIDE 这个关键字所描述的位移数量的单位是来自于 SATTR 这个关键字指定的属性 (row-num) , 
(这个 row-num 在本文中结合上下文环境来考虑是元组自身, 即,用来描述元组有多少个,元组的数目) 
(切分窗口的数据流.元组.属性字段 != 滑动窗口的数据流.元组.属性字段)


In such a case, each tuple arrival introduces a new window extent that has length RANGE and ends at the newly-arrived tuple, as shown in query Q3 below. 
在这种场景下,每个到达的元组都会构建一个新的窗口范围,这个窗口范围的长度通过 RANGE 关键字来描述, 并且再有新元组到来之后该窗口范围便会截止/关闭(就是后续到来的元组不往这个窗口中追加),新到达的元组会构建新的窗口,这个场景描述语句 Q3如下所示:


Q3: SELECT seg-id, count(*)
FROM Traffic [RANGE 300 seconds # 窗口范围为 tuples in stream which tuples.ts in [tuple.ts, tuple.ts + 300 seconds]
RATTR ts # tuple.ts 
SLIDE 1 row # 每次窗口向后滑动 1 个 tuple 
SATTR row-num] # count(tuple) = 1

```
先来整理下思路, 到这个地方依次讨论了,整个数据流, 整个数据流的窗口划分滑动是基于相同 元组.属性 的， 整个数据流的窗口划分滑动分别基于不同的 元组.属性 (语义拆分了)
然后到了这里, 又将整个数据流按照 partition 分区进行了更细致的切分, 我觉得分区切分这里本质是为了考虑数据流中在单个窗口范围内数据的一个并行操作打基础
即,window 可以理解为对数据流纵向的一个切分, 被切分的数据都在同一个时间段内(假定 切分的属性是时间戳 吧)
然后, 在 window 内部再对数据进行 横向切分, 再加个多线程并发, 控制好开头任务分发, 和结尾每个并发单元收个结果，再汇聚下就能对这个 window 中的数据处理实现个并行啥的
这个地方和 Spark-Streaming/Structured Streaming 中的 DStream 中的 partition 不是一回事么 
后来回来看了下这里自己的推论, 发现这里对分区理解的不到位, 上述的理解并不到位
```

A partitioned window aggregate query use an additional partitioning attribute, PATTR, to split the input stream into sub-streams before applying the 
other parameters in the window specification to each sub-stream.
基于分区的窗口聚合查询中, 会使用额外的分区属性, 通过 PATTR 这个关键字来描述, PATTR 能够将上游输入数据流在执行窗口说明的参数操作前先将数据流根据数据流.元组.属性集合中的某个属性
值的分布将数据流切分成粒度更细的 子数据流(其实也是 元组子集)

Q4, show below, is identical to Q2 except that the seg-id is now a partitioning attribute instead of a group-by attribute.
下面的 Q4 这个查询语句, 和 Q2 很相似, 除了 Q2 语句中是对 seg-id 这个属性执行分组(group-by) 操作的, 而 Q4 则是对 seg-id 执行分区(partitioning)操作 而已.

Q4: SELECT seg-id, count(*)
FROM Traffic [RANGE 1000 rows
SLIDE 10 rows
WATTR row-num
PATTR seg-id] # so , the PATTR is the attribute of partition by which the tuple attribute partitioned ?

This change in the window specification leads to significant changes in the window semantics. 
这种对数据流窗口说明中的窗口进行更细粒度的分区这里的改进在窗口语义定义上的变动起着至关重要的影响. 
(其实通过这句话多少可以推断出, 窗口语义的定义 之所以今天能够给出明确的定义 和对 数据流进行分区 这一想法的提出有着至关重要的促进作用)

```
到这里就开始讨论, 分区 不分区 在执行查询的时的实现细节了(虽然还没往下看,但我觉得肯定和并行度或者是每次处理数据粒度而开辟的缓存空间大小这里脱不了干系(然而并不是..))
```

Q2, a non-partitioned query takes a sequence of 1000 tuples from input stream as a window extent, 
then divides those 1000 tuples into groups by segment id and counts the vehicles in each group. 
对于 Q2 这种没分区的查询, 每次会从数据流中依次加载 1000 个元组来构建一个窗口范围,
然后将窗口范围内的这 1000 个元组根据 元组.seg-id 这个属性进行分组, 然后对每个组中的元组数目进行统计, 好计算出每组中有多少个元组来作为统计该 seg-id=xxx 的机车数量有多少.

In short, Q2 first computes the window extent and then divides the extent into groups. 
简而言之, Q2 查询操作中先划分了窗口范围, 在将窗口范围中包含的元组根据 seg-id 字段划分成组.

In contrast, Q4 first divides a stream into "partitions" (sub-streams) by partitioning attribute, and then divides each partition into window extents
independently, based on the other three parameters in window specification. 
而相对地, Q4 查询中首先将数据按照 seg-id 这个分区属性进行划分成不同的分区, 然后再将每个彼此独立的分区 基于窗口说明中的 3 个参数（RANGE,SLIDE,WATTR) 
继续分割构成不同的 窗口范围.

Note that for time-based window queries, the PATTR parameter does not bring more expressive power -- the effect of a PATTR attribute is the same 
as a group-by attribute. 
需要谨记这一点, 基于时间窗口 的查询中, PATTR 这个关键字参数并不会带来更强大的语义 -- PATTR 关键字对的元组中的属性字段所做的操作其实是等价于 group-by 操作的. 
(这句话的作用是, PATTR 这个关键字之所以在上面的那个事例中显得这么有用,并非语义自身强大, 它的作用实际上和 gropy-by 部分上下没有好坏之说)

Discussion: Our window specifications are similar to the window construct in CQL (Continuous Query Language), a SQL-based language for expressing 
continuous queries over data streams. 
讨论: 我们的 窗口说明 相关这些概念和 CQL 语句中建立描述窗口对象使用的语句十分相似, 其中 CQL 又叫持续查询语句, 它是一种支持对数据流执行连续查询操作的类 SQL 查询语句.


Our window specification differs from it in the use of explicit user-specified WATTR and SLIDE parameters, whereas the published version of CQL 
assumes a "slide-by-tuple" window semantics and uses a pre-defined timestamp or tuple sequence number as the windowing attribute. 
我们提出的窗口说明和 CQL 中给出的窗口说明有这样的不同, 在我们的窗口说明中会明确地支持用户自定义 WATTR 和 SLIDE 参数, 而目前发布的 CQL 版本中已经明确表示
他们支持 "以元组-为窗口-滑动参照单位" 这样的窗口语义并且使用一种 预先定义好的时间戳或是元组系列号来作为窗口的特征.
(这个地方总结一下 CQL 窗口定义和 文中给出的窗口定义的 2 点不同之处)
CQL 中窗口定义语义
1. 将窗口滑动的参照属性写死为 元组, 也就是每次滑动窗口的时候，只能按照数据流中的元组为粒度来滑动
2. 将窗口划分切割的参照属性写死为 
2.1 内部定制化的时间戳字段
2.2 内部定制化的元组序列号
文中提出的窗口定义语义
1. 将串钩滑动的参照属性: 支持用户自定义
2. 窗口划分切割的参照属性: 支持用户自定义

SQL-99 defines a window clause for use on stored data.
在 SQL-99 标准中给出的窗口用于存放数据的窗口定义语句.

SQL-99 limits windows to sliding by each tuple (i.e., each tuple defines a window extent), thus tying each output tuple to an input tuple. 
SQL-99 标准中对窗口语义的定义 限制了以每个元组作为滑动单位的滑动窗口的使用(也就是说, 基于将单个元组定义成窗口范围),因此将每个输出元组和单个的输入元组绑定在了一起. 
(造成了一定程度上的耦合)

We call such windows data-driven. 
我们将 SQL-99 中所给出的窗口语义定义称为 数据-驱动型 窗口语义.

In comparision, stream queries often use domain-driven window semantics where users specify how far the consecutive window extents are spaced from each other in terms of domain values. 
与此相对应的, 在数据流的查询操作中 通常会使用 域-驱动型 的窗口语义, 在这种窗口语义中由用户根据 域-值(其实也是元组中某个属性字段) 的分布情况来指定 连续的窗口范围 彼此之间的跨度大小.

We believe domain-driven windows are more suitable for applications with bursty or high volume data. 
我们认为基于 域-驱动 的窗口语义定义 更加适合于处理 数据量突然激增 或是 高维度数据 的数据流中.
(高维度数据: 元组的 schema 中包含嵌套类型例如一个字段嵌套了好多层的 map/array 这种, 或是元组中包含许多属性字段 都可以将其看作是高维度数据)

Consider a network monitoring application -- one possibly wants network statistics updated at regular intervals, independent of surges or lulls in traffic. 
假定有这样一种网络监控程序 -- 某人可能希望无论是网络流量高峰、还是低谷期, 网络数据中的某个指标数据都能够规律性地刷新生成.

A variation of our window specifications is to use functions in window specifications. 
对于这个问题的情景, 我们提出的窗口说明支持这样的变种: 即, 将作用于元组中某个属性字段的函数应用到窗口说明中.

For example, the following Q5 is a variation of Q3.
例如,下面的 Q5 便是适合上述情景的 Q3 查询语句的变种:

```
Q5: SELECT seg-id, count(*)
FROM Traffic [RANGE 300 seconds
RATTR ts 
SLIDE 5 rows 
SATTR rank(ts)]
```

The function rank(ts) maps each tuple t in the input stream to its rank in order of ts attribute values. 
rank(ts) 这个函数会将输入数据流中的元组 t 中的时间戳 ts 这个字段传入到 rank 函数中来获取该时间戳在当前 窗口 RANGE 范围内的局部排序数值,
然后我们将这个数值赋值给窗口查询的 SATRR 这个关键字, 最后滑动窗口会按时间戳排序的顺序 依次顺序滑过 5 个旧的时间戳,进来 5 个新的时间戳之后再局部排序，依次进行.


So instead of advancing a window based on tuple arrival order, we advance it based on the logical order implied by ts. 
所以, 与其将窗口基于元组到达的顺序进行排序(这个可是物理层面的),我们在逻辑层面上根据时间戳来对数据元组进行排序.

So, the window in Q5 is of the length 300 seconds over the ts attribute, and slide by 5 rows over the logical order defined by ts. 
所以在 Q5 查询语句中,我们所构建的窗口是: 
基于上游输入数据流.元组.ts (时间戳) 进行窗口划分,
窗口划分的跨度是 ts 300 秒, 即 ts in [currentTimestamp , currentTimestamp + 300s] ==> a window extent 
窗口向后滑动的步频是 按照当前窗口范围内 ts 属性字段升序排序这样的顺序, 每次滑动 5 个时间戳, 其中升序顺序通过 rank 这个函数计算得出.


Conceptually, this window suggests sorting before windowing, similar to the window clause with the ORDER BY construct in SQL-99.
理论上来说, 窗口语义中先对窗口进行排序然后再划分窗口范围, 这种处理方法与 SQL-99 标准中提出的基于窗口对象的 ORDER BY 操作是十分相似的.

In this paper, we only consider rank(RATTR) -- the attribute defining the slide order needs to aggree with the range attribute. 
在本篇文章中, 我们只考虑了对 元组中的滑动参考属性进行 rank 的操作, 并且需要保证这一点 -- 窗口范围划分所参照的 元组.属性 和 窗口滑动所参照的 元组.属性 二者之间必须达成关联.
(这个地方大概是强调 SATTR 中调用的函数传入数据必须是有 RATTR 这个关键字指定的 元组.属性, 而返回的数值必须满足 SLIDE 控制窗口滑动的单位的要求, 比如说 rank(x) 
计算得到一个 0.8 和 -100 这种数据传递给 SLIDE ？row 都是非法的, 从语义上便说不过去, 如果将 SLIDE 支持的语义中窗口移动的单位是一个数值集合 X 的话, 那么必须保证 rank 也好
还是什么其他调用函数也好, 通过该函数所计算处理的数据集合 Y 必须是 X 的子集才行)

(update date 2018/10/18)
有一段时间没有看论文了, 本文中提到的 Q1 的流式查询 SQL 语句重新梳理下

```
Traffic table schema: 
{
seg-id: String, 
speed: Double, 
ts: Timestamp  # pattern HH:MM:SS 
}

Q1: SELECT seg-id, max(speed), min(speed)
    FROM   Traffic [
                    RANGE 300 seconds
                    SLIDE 60  seconds
                    WATTR ts
                   ]
    GROUP BY seg-id 
```
* SQL 语句直译: 
               持续从数据表 Traffic 中 seg-id, 最大 speed 数值, 最小 speed 数值,
               且保证每次查询中控制表中的 ts 这个属性字段所在的时间范围是 [查询发起时间 -300s, 查询发起时间] 
               且保证时间范围的计量方式与时间范围计量方式相同(时间单位相同)
               且每 60s 进行一次上述查询, 更新计算结果

* SQL 语句解析理解: 
               这个查询是一个基于时间属性的窗口查询, 窗口的计量单位是 seconds, 窗口时间跨度为 300 seconds,即,通过 SQL 语句中的 RANGE 关键字来标识,
               窗口向前滑动步长是 60 seconds , 即, 通过 SQL 语句中的 SLIDE 关键字来标识, 
               窗口的计量单位是 seconds 是通过 SQL 语句中的 WATTR 关键字来得知的, 
               该 SQL 语句支持粒度更细的查询语义, WATTR 实则是 window-attribute 的缩写, 
               如果希望将窗口跨度和窗口向前滑动步长分开统计查询的话, 也可以为其各自指定窗口属性类型, WATTR -> [RATTR, SATTR] 
               RATTR 对应的 range-attribute, 而 SATTR 对应的 slide-attribute ,
               用这两个类型的 attribute 可以分别指定 窗口跨度计量单位, 和窗口向前滑动的计量单位,例如我希望窗口跨度,也就是窗口范围是以表中的 ts 属性字段来指定的, 
               而, 每次窗口向前滑动是以数据表中的 Row 向前推动, 那么便可以使用如下的 SQL 语句
               ```
               Q3: SELECT seg-id, count(*)
                   FROM   Traffic [
                                    RANGE 300 seconds 
                                        RATTR ts 
                                    SLIDE 5 rows 
                                        SATTR row-num
                                  ]
               Q3 SQL 语句直译: 从 Traffic 表中以属性 ts 字段作为窗口范围计量单位, 每次基于 ts 在 [执行查询时间点 -300 seconds, 执行查询时间点]
                               范围内执行查询, 将这个时间段的 seg-id 和表中的记录条数查询出来, 并且查询持续执行, 每次向前推动 5 个记录条数
                               注意这里窗口向前滑动的计量单位已经从 Q1 中的 seconds 时间计量单位调整成了记录条数, 而这个便是通过 SATTR(slide-attribute)来设定的
               ```











----

文章看到这里最大的感受就是, 每篇论文在阐述概念的时候都是先举一个案例,
然后基于这个案例，先抛出一个抽象描述,后面结合这个最开始抛出的案例给出特例说明防止读者读完抽象描述不理解, 或是加固读者将抽象用在普通说明中的理解
如果在给出抽象描述这里, 自己先思考下可能性, 以及觉得有问题的地方, 再读后面的 For example.. 这里会发现很多提出的问题都有相关的答案,

再就是, 文章整个结构逻辑性很强, (前因,后果, 假设,论证都很到位,找不到任何一处作者提出了某个观点,根本不会有在后续文章中找不到证明这个观点正确,
错误的证明的地方存在,) 这种逻辑性不仅仅体现在文中句子句子之间, 章节章节之间, 还体现在这篇论文和后续基于这篇论文不足而提出的新论文中. 

比如 punctuation 这个概念, 在本文中根本没有详细的说明, 如果不是读了前作很难从本文中找到 punctuation 更详细的定义(不过基本的概念是有给出的),
以及要读的后面一篇论文中所提到的 WID 中仍旧存在的问题和解决方法, 如果不是读了这一篇,估计对下一篇论文的理解也会存在很大问题.

还有一个地方大概是, 此类论文中有个特点, 在文章的一开始给出的案例和问题阐述中会各种术语各种的用, 
这样会让读者因为一开始不明所以很难读下去,但是只要读到后面就会了解到前面所说的是什么, 后面会逐一说明的, 
文章之所以这么写其实是为了让读者带着问题思考着来看这篇文章, 
不过我一开始看 Q1 这个查询语句中的 SLIDE,RANGE 这些的时候已经不是在思考本篇文章的问题了,思考的是人生(因为看的怀疑人生).