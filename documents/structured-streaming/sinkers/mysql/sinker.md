## Structured Streaming sinks to MySQL 

### 测试使用 事务 的方式来避免将重复数据推送至 mysql 表中
#### 环境要求


#### MySQL 环境准备
##### MySQL 源码安装及初始化过程
##### MySQL 中创建表
##### MySQL 与 MySQL 客户端创建连接

#### 上游 Kafka 数据推送模块编译 && 启动方式说明


#### Structured Streaming 算子计算逻辑说明 && 编译 && 启动方式说明


#### 问题说明
1. 数据在经由 kafka streaming 推送至 Structured Streaming 进行计算的时, 会出现数据重复写入 MySQL 的情况


#### 问题分析

#### 可能解决方案(alternatives) 


#### 实际测验步骤
```
* 首先, 和我同步问题的同学已经说明数据记录重复生成是因为上游数据源提供方提供的数据条数存在重复导致的, 而并不是 structured streaming 自身计算问题导致的, 
* 所以, 对于数据重复这个问题我们不过深讨论 structured streaming 处理数据流经由 sinker 同步 mysql exactly-once 等 WAL 记录提交次数等语义问题,
* 以及, 数据去重这种属于数据清理的处理环节应该加到 streaming 开始读取阶段, 而不应该在最后结果落外存的时候来处理.(不然一旦计算涉及到数据聚合会严重影响到计算结果的正确性, 上游输入数据都不正确,还想计算结果能正确是不可能的)
* 不过，在这里我们先不考虑这些, 而是将问题中心放到: 万一计算这里处理不当导致数据结果条数重复的情况发生, 使用 mysql + 事务 是否能够避免重复的数据记录提交至 mysql 中.
```

1. 推送数据 schema 说明
```$xslt
kafka 端推送数据格式
{
  id:String,      # kafka key 用于 kafka 自定义散列分区
  msg:String,     # 
  timstamp:String # yyyymmddHHMMSS
}
```

1. 严格控制
首先, 实现简单逻辑的 mysql sinker 将数据记录提交到 mysql 表 ```sparkdata1``` 中




#### references
[Spark Streaming Crash 如何保证 Exactly Once Semantics](https://www.jianshu.com/p/885505daab29)


