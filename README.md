## 问题背景描述

* 初期问题描述: spark-streaming 算子运行初期计算结果周期性汇总打印输出, 但程序运行期间 Heap 不断增加, 最后耗尽内存后 OOM 异常退出
* 上游数据量, kafka 上游不间断(Thread.sleep(0))写入数据总条数 10万 条


## 初步判断问题有以下 3 个
> 1. OOM 问题出现在 spark-streaming 父 RDD 清理频率过低, 依赖(dependency 过长) 导致内存耗光
> 2. 每次迭代循环过程中 RDD 没用通过 drop 方法释放空间
> 3. 没用通过 --driver-memory 来限制 spark app 使用堆栈空间上限导致 spark app 耗光 JVM 进程中堆栈空间而异常退出



## 针对上述问题相关 3 个解决方法
> 1. 通过构建 spark-streaming 上下文环境(StreamingContext) 的时候设定 remember 成员变量为 3ms 来降低父 RDD 依赖保存时间，并将 SparkConf 配置字段
 
 ```
  conf.set("spark.streaming.unpersist", "true")
 ```
 来保证 RDD 缓存空间及时清理, 参考文档 [Spark-Streaming 数据清理机制](https://www.jianshu.com/p/f068afb23c77)

> 2. 每次在执行 df.rdd 赋值给 lastRdd 后执行 df.drop() 方法, 目的为释放空间
> 3. 在提交参数的时候, 使用 --driver-memory 512M 来防止 JVM 中的桟空间打的过满


执行上述方法之后, 继续在 linux 上进行本地(--master local[*])测试

* 上游 kafka 发送数据量总量为 800w 条, 5 个节点, topic partition=5，replica=2, 写入过程中 Thread.sleep(0)
* 堆栈空间未见上升, 始终维持在日志文件 [driver-jvm.log](https://github.com/Kylin1027/spark-streaming-repo/blob/master/doc/log-info/aimer-driver-spark.log)
* 运行时间 1 小时左右程序卡死, 卡死前异常信息如下

```
18/09/20 01:41:59 INFO TaskSetManager: Finished task 199.0 in stage 21852.0 (TID 42054) in 540 ms on localhost (executor driver) (197/200)
18/09/20 01:41:59 INFO ShuffleBlockFetcherIterator: Getting 1 non-empty blocks out of 200 blocks
18/09/20 01:41:59 INFO ShuffleBlockFetcherIterator: Started 0 remote fetches in 0 ms
18/09/20 01:41:59 INFO Executor: Finished task 80.0 in stage 21852.0 (TID 42055). 94829 bytes result sent to driver
18/09/20 01:41:59 INFO TaskSetManager: Finished task 80.0 in stage 21852.0 (TID 42055) in 1212 ms on localhost (executor driver) (198/200)
Exception in thread "streaming-job-executor-0" java.lang.Error: java.lang.InterruptedException

```


## 测试环境版本

* Spark [2.2.0,2.3.0]
* kafka 0.11.0.0 
* jdk 1.8.x
* maven [3.9,)


## 问题复现的方式

* 创建 kafka topic 

```
 bin/kafka-topics.sh --zookeeper ${zk-broker-list}  --create --topic ${topic_name}  --partitions ${partition-num}  --replication-factor ${replication-factor}
```

* 修改代码中的 [KafkaDirectDemo.scala](https://github.com/Kylin1027/spark-streaming-repo/blob/master/src/main/scala/com/spark/aimer/debug/KafkaDirectDemo.scala) 

```
 val topic = "${set your kafka.topic here}"
 val broker = "${set your kafka.bootstrap.servers here}"
 val group = "${set your group.id here}"

```

* 修改代码中的 [KafkaProducr.scala](https://github.com/Kylin1027/spark-streaming-repo/blob/master/src/main/scala/com/spark/aimer/debug/KafkaProducer.scala)

```
val brokers = "${set your kafka.bootstrap.servrs here}"
val topic = "${set your kafka topic here}"
```

有必要的话也需要调整下发送 kafka 上游数据的总量和中途 Thread.sleep 的时间, 根据开发机实际的情况


* 将 maven 镜像库设定为 aliyun 镜像库, 如果有必要的话


* 编译代码得到可执行文件  kafka-streaming-1.0-SNAPSHOT-jar-with-dependencies.jar 

```
 mvn clean install 
```

* 启动 kafka producer 推送数据

```
 nohup java -cp ./kafka-streaming.jar com.spark.aimer.debug.KafkaProducer  &
 tail -f nohup.out 

```

* 修改 conf/spark-defaults.conf 配置文件增加 driver jvm 日志打印参数

```
 
 spark.driver.extraJavaOptions -Xloggc:aimer-driver-spark.log -XX:+PrintGCDetails -XX:+PrintGCTimeStamps

```


* 启动 kafka-streaming spark app 周期性定于消费计算数据


```
 rm nohup.out 

 nohup  ../spark-2.2-x/bin/spark-submit \
        --master local[*] \
	--class com.spark.aimer.debug.KafkaDirectDemo \
	--driver-memory 512m \
	./kafka-streaming-1.0-SNAPSHOT-jar-with-dependencies.jar   & 
```

## 问题进展
### 通过 Structured Streaming 来实现原有逻辑
* 新增代码 [KafkaStructuredSolution1](https://github.com/Kylin1027/spark-streaming-repo/blob/master/src/main/scala/com/spark/aimer/structured/KafkaStructuredSolution1.scala) 其中为 SparkStreaming 中逻辑实现(在原有代码 '加油' 作者基础上做了些调整与优化)
* 优化原有数据字段解析, 考虑到原有 ``` json-lib-jdk15 ``` 在 spark-shell 环境下执行时会因为缺少一些列的库而报异常,如
   ```
   java.lang.NoClassDefFoundError: net/sf/ezmorph/Morpher
   ```
   而使用 alibaba fastjson 

* 新增了 worksheets 路径文件夹, 其中会将实验中测试 API 使用方法的 demo 加入    
   
* 目前整个代码逻辑通过 linux 本地模式启动 spark-shell 可正常运行， spark-shell 启动命令如下
    ```
     ./bin/spark-shell  --master local[*] --jars ./jars/fastjson-1.2.49.jar  
    ```
    在这里需要通过 --jars 将 fastjson 引入到 spark-shell 上下文执行环境中,否则运行 import 语句的时候会报 
    ``` class not found ``` 的关异常
* 目前尚未编译生成 jar 包, 后续会针对该算子进行 jvm 各项压测,及运行时长是否卡死等各方面后与 spark-streaming 进行对比
 

## 尚不明确的问题有
* Structured Streaming 与 Spark Streaming 清理内存空间策略
* 导致 Spark Streaming SQL 执行期间内存打满究竟是由 RDD lineage 依赖过长, 
  还是由 Spark SQL 执行 JOIN 操作时内存分配和回收的策略影响导致
* 为何 Spark Streaming 中通过 unpersist 和 remember 来提高 MetaData 清理频率最后还是因为内存耗光而卡死  

# END
