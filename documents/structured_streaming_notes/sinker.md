## 记录 Structured Streaming 中不同 Sinker 数据同步方法

## 基本环境
* jdk 1.8 
* spark [2.2, +]
* kafka [0.10,0.11]_2.11

#### NOTE
1. 没有特殊说明, 上游输入流均为 kafka , 且数据字段内容为简单结构 ${id}:String,${msg}:String,${timestamp}:String 


### HDFS Sinker 
#### 要求
上游数据流在加载后, 计算流构建 DataFrame 生成结果后, 在写入下游文件的时,当前时间构建时间戳将文件根据时间戳写入到指定目录下游.

#### 实现方法
1. 使用默认的 Structured Streaming 来完成数据流写入到 HDFS  中, 以 csv/parque/text 等不同格式
2. 由自己来实现 Hadoop API, 每次以追加 append 的方式调用 foreach(ForeachWriter[T]) 来将数据追加到同名文件中去(有可能会存在语义问题)
3. 将数据同步至 kafka, 然后通过一个  kafka2hdfs 的 CT 程序按照时间戳进行按批次同步至 HDFS 指定时间戳的路径下
4. (可行) 仍旧使用 FileStreamSinker 这个类中提供的方法, 并且将原有的 data.csv 文件名称使用 函数调用返回 String 来替换, 以确保返回的时间会随着当前时间的实际数值进行变动



#### 执行步骤
* 首先, 将 [Producer](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/kafka/Producer.scala) 代码中的 brokers, topic 设定为实验 kafka broker 和 topic 
* 重新编译程序 ```mvn clean install``` 得到 xxx-with-dependencies.jar 
* 执行  ```java -cp ./kafka.jar  com.spark.aimer.kafka.Producer``` 命令来启动 Producer 进程向 kafka 指定 topic 中推送数据

##### alternatives

* <b>备选方案 1</b> 代码实现如 [KafkaSourceToHdfsSink](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/structured/sink/KafkaSourceToHdfsSink.scala) 
* <b>备选方案 1 实际执行结果</b>: 虽然指定了文件名称为 xxx.csv 文件, 但是在实际运行中产出结果仍旧会将设定的 xxx.csv 文件名下,每个 partition 会创建 part-*.csv 格式的文件

```$xslt
drwxr-xr-x   2 xxx xxx         0 2018-10-17 01:33 /app/business/haichuan/cbc/aimer/spark_output_data/data.csv/_spark_metadata
-rw-r--r--   3 xxx xxx       4389 2018-10-17 01:33 /app/business/haichuan/cbc/aimer/spark_output_data/data.csv/part-00000-3b763457-c252-4e12-9ab9-d2370f837449-c000.csv
-rw-r--r--   3 xxx xxx         37 2018-10-17 01:33 /app/business/haichuan/cbc/aimer/spark_output_data/data.csv/part-00000-9fbd38ea-d333-48e2-99f9-6c25c179fe05-c000.csv
-rw-r--r--   3 xxx xx         37 2018-10-17 01:33 /app/business/haichuan/cbc/aimer/spark_output_data/data.csv/part-00000-d4224592-49d1-4b03-a43e-1824c7510cdf-c000.csv
```
* <b>备选方案 1 分析</b>, 在 FileStreamSinker 执行的时候, 底层仍旧是将数据切分成 partition 来进行小粒度的计算, 每个 task 的计算任务无法串行写入到 1 个文件中, 且我们使用的是高级 API Stream 无法
  通过调用重新分区的方法来设定最后落 HDFS 这步操作中只有 1 个 task 写入数据.

* <b>备选方案 2</b>: 自行实现 Hadoop API 再通过 foreachWriter 方法来调用, 因为 spark 自身 task 失败重试有可能会因为数据写入不幂等的情况, 且开发成本较大最后考虑

* <b>kafka2hdfs</b> 这个过程中,需要将 kafka 中在某个时间段内的全部数据全部获取, 并不符合 kafka 提供 API 的特点, 这种全局获取数据的方式应该放到 spark  计算引擎中来而不是外存中, pass 

* <b>通过变量来实时修改路径地址</b> 可行,这种方法正在测试中, 如果正常将会每隔一个时间周期指定一个新的时间戳格式的路径地址, 而类似 part-xxx.csv 这种并行写入的文件便可以归属到同一个时间段的时间戳文件夹下 

通过将获取当前时间戳方法调用作为路径名称进行替代后, 能够得到写入数据的格式如下, 值得注意的是, 在这里需要根据实际需求来对生成时间戳
的函数进行升级, 同时也需要考虑到 trigger 触发周期时间对实际时间戳生成影响等等

```$xslt
./bin/hadoop fs -ls /app/business/haichuan/cbc/aimer/spark_output_data/
drwxr-xr-x   2 xxx xxx          0 2018-10-17 02:07 /app/business/haichuan/cbc/aimer/spark_output_data/20181017020656
drwxr-xr-x   2 xxx xxx          0 2018-10-17 02:19 /app/business/haichuan/cbc/aimer/spark_output_data/20181017021907
drwxr-xr-x   2 xxx xxx          0 2018-10-17 01:34 /app/business/haichuan/cbc/aimer/spark_output_data/data.csv
drwxr-xr-x   2 xxx xxx          0 2018-10-17 01:36 /app/business/haichuan/cbc/aimer/spark_output_data/parquet.csv


./bin/hadoop fs -ls /app/business/haichuan/cbc/aimer/spark_output_data/20181017020656
drwxr-xr-x   2 xxx xxxx          0 2018-10-17 02:06 /app/business/haichuan/cbc/aimer/spark_output_data/20181017020656/_spark_metadata
-rw-r--r--   3 xxx xxx        419 2018-10-17 02:07 /app/business/haichuan/cbc/aimer/spark_output_data/20181017020656/part-00000-bed75a40-2e9c-49eb-8296-fde0d2700a6d-c000.snappy.parquet

```