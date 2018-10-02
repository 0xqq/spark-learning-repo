## 环境
* spark 2.2 
* kafka [0.11,0.10]_2.11
* jdk 1.8 
* zookeeper 3.4.6 

## 问题描述
### spark streaming 

#### 问题现状: spark streaming 每次将计算结果写入至 kafka 时会存在数据重复 && 丢失的问题


#### 问题复现方法
1. 首先启动 spark-streaming-app
2. kafka producer 开启数据推送, kafka producer 数据结构体格式 , kafka-topic: kafka-in-stream: partition 5， replication 2
```$xslt
{
 id:String , # 同样作为 hash 分区的关键字
 msg:String , # 携带消息内容
 timestamp:String # yyyy-mm-dd HH:MM:SS 格式的时间戳字符串
}
```
该结构体信息每 1 秒钟发送 1 条，
3. 数据流经由 spark-streaming-app 开始处理, 根据分钟级进行数据聚合计数操作, 然后将数据
```$xslt
{
 timestamp:String # yyyy-mm0dd HH:MM 格式时间戳字符串
}
```
写回到 kafka-topic: kafka-out-stream, partition 5, replication 2
在这里分别使用 spark streaming 自带的 checkpoint 来记录每次的 offset 的发送记录情况

4. 使用 consumer 将 kafka 中的数据读取出来, 排除数据流的开始和结束，其余时间戳关键字对应的 count 应该都是 60 ， 对应上游 kafka 数据
  每 1 秒产生 1 条数据(如果问题无法复现, 将会调整时间戳格式增加毫秒字段,同时上游数据生成方式每 0.1 秒生成 1 条数据) 


#### 原因

* 数据的重复生成与订阅, 目前推测的原因是因为 spark streaming 发送数据时, 重启之后  kafka offset 与 spark streaming checkpoint 记录不同步导致,所以通过将 offset 统一通过定制方法记录到 zookeeper 中来解决


#### 解决问题方法
* 将每次 spark-streaming 作为数据订阅 consumer, 每次处理完业务逻辑之后, 会将此次的消费进度同步至 zookeeper 上, 再等到 spark-streaming 恢复的时候, 从 zookeeper 上加载 offset 来进行初始化

### spark structured streaming 
#### 问题现状 spark structured streaming 再将计算结果写入 kafka 时存在着数据延迟的问题
#### 问题复现方法
1. 首先启动 spark structured streaming app 
2. 开启上游 kafka 进行数据推送, 上游数据格式
```$xslt
{
 id:String, 
 msg:String,
 timestamp:String # yyyy-mm-dd HH:MM:SS
}
```
3. kafka 上游没生成一条数据, 会在 mysql 数据库 kafka 表中创建 1 条记录项
```$xslt
db schema 
id:String # primary key
product_timestamp:java.sql.Timestamp 
spark_consume_timestamp:java.sql.Timestamp
```
检查 product_timestamp 和 spark_consume_timestamp 这两个时间戳的时间差

#### 原因, 官网给出 trigger + foreachWrite sink 的方式存在 100ms 的延迟, 有可能是这里的问题导致的


#### 解决问题的方法
解决问题的方法目前想到 2 个
* 首先,可以通过替换 mysql 为 kafka 同时将 foreachWrite sink 替换为 kafka sink 并将 trigger 这里替换为延迟只有 1ms 的类型
* 如果不替换 mysql 作为下游数据接收方的话, 需要调研 foreachWrite 这里是否可以将粒度细化为 partition, 通过 partition + 长数据库连接的方式来进行数据写入 mysql 操作
