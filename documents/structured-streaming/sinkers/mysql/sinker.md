## Structured Streaming sinks to MySQL 

### 测试使用 事务 的方式来避免将重复数据推送至 mysql 表中

#### 涉及到 repo 中的代码
* [mysql unit test](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/test/scala/com/spark/aimer/tests/DataBaseDemoTest.scala)
* [kafka producer](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/kafka/Producer.scala)
* [structured streaming](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/structured/sink/KafkaSourceToMySqlSink.scala)

#### 环境要求
* spark [2.1.x,)
* maven 3.3.9 
* jdk 1.8.0_171
* kafka [0.10, 0.11]_2.11
* scala 2.11 
* mysql 5.6 
* mysql client DBeaver 5.2.2 

#### MySQL 环境准备
##### MySQL 源码安装及初始化过程
1. 下载 mysql 源码
```
wget http://mirrors.ustc.edu.cn/mysql-ftp/Downloads/MySQL-5.6/mysql-5.6.39.tar.gz
```
2. 编译 && 安装（跳过编译安装 mysql 依赖 lib 的步骤）
```
./configure  --prefix=/home/work/kylin/mysql \
    --with-unix-socket-path=/home/work/kylin/mysql/sock/mysql.sock \
    --with-plugins=innobase \
    --enable-profiling \
    --with-charset=gbk \
    --without-readline \
    --without-libedit \
    --with-extra-charsets=gbk,utf8,ascii,big5,latin1,binary,gb2312 \
    --enable-local-infile --enable-thread-safe-client
```
3. 启动 mysql 服务进程
说明, 此处默认加载的是 /etc/my.cnf 该配置文件中的配置信息
```
./bin/mysqld_safe & 
```

4. 设定登录用户名称 && 授权远程用户登录
```
# 设置 aimer 用户免密登录
./bin/mysql -uroot -Dmysql -e"grant all on *.* to 'aimer'@'%' identified by '';"   

# 设置 dbproxy 用户可以远程登录, 通过这个命令能让笔记本上的 mysql 客户端连接 mysql 数据库 
./bin/mysql -uroot -Dmysql -e"grant all on *.* to 'dbproxy'@'%' identified by 'xxx'"
```
5. mysql 安装完成

##### MySQL 中创建表
```$xslt
CREATE TABLE spark.`data1` (
	id varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
	msg varchar(1000) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
	`timestamp` varchar(500) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL,
	CONSTRAINT `PRIMARY` PRIMARY KEY (id)
)
ENGINE=InnoDB
DEFAULT CHARSET=utf8
COLLATE=utf8_general_ci
COMMENT='';
```

#### 上游 Kafka 数据推送模块编译 && 启动方式说明
```$xslt
1. git clone https://github.com/Kylin1027/spark-learning-repo.git
2. cd spark-learning-repo 
3. update com/spark/aimer/kafka/Producer topic && broker list to your own 
3. mvn clean install
4. java -cp com.spark.aimer.kafka.Producer 
```

#### Structured Streaming 算子计算逻辑说明 && 编译 && 启动方式说明
<b>说明: 这里需要注意的是</b>
* 1. unit test：
> 从本次提交之后增加了 scalatest 选项, 在正常执行 mvn clean install 的时候会自动触发 unit test 计算逻辑, 
> 且unit test 中有连接数据库, 提交数据的相关逻辑实现, 如果数据库等相关环境没有准备就绪, 
> 故,建议首次编译的时候使用如下命令来跳过单元测试环节
```$xslt
mvn clean install -DskipTests=true 
```

* 2. 环境相关的变量信息: structured app 代码中的如下信息需要替换成你自己开发环境中的配置信息, 以确保代码可以正确执行
> 2.1 kafka broker list 
> 2.2 kafka topic 
> 2.3 mysql sinker url(jdbc:xxx), username, password 
> 2.4 writeStream 中的 checkpoint 路径


#### 问题说明
数据在经由 ```kafka producer``` 推送至 ```Structured Streaming Spark App``` 进行计算, 最终经由自己实现 ```ForeachWriter MySQLSinker```
将数据推送至 Mysql 数据库表的时候会出现相同数据数据重复写入的情况, 现希望将去重逻辑放到 mysql 提交数据的特点来完成对重复数据去重

#### 问题分析

```
* 首先, 和我同步问题的同学已经说明数据记录重复生成是因为上游数据源提供方提供的数据条数存在重复导致的, 而并不是 structured streaming 自身计算问题导致的, 
* 所以, 对于数据重复这个问题我们不过深讨论 structured streaming 处理数据流经由 sinker 同步 mysql exactly-once 等 WAL 记录提交次数等语义问题,
* 以及, 数据去重这种属于数据清理的处理环节应该加到 streaming 开始读取阶段, 而不应该在最后结果落外存的时候来处理.(不然一旦计算涉及到数据聚合会严重影响到计算结果的正确性, 上游输入数据都不正确,还想计算结果能正确是不可能的)
* 不过，在这里我们先不考虑这些, 而是将问题中心放到: 万一计算这里处理不当导致数据结果条数重复的情况发生, 使用 mysql + 事务 是否能够避免重复的数据记录提交至 mysql 中.
```

只要是问题和 spark structured streaming 底层 exactly once 语义无关的话, 这个问题是很好解决的, 
也就是说数据的重复是由用户自身使用不当, 导致的数据重复问题, 以及 structured streaming 在处理 exactly once 这里所做的相关工作已经很到位, 
通常情况下是不会出现因 structured streaming 底层调用方法而引发的数据重复的问题. 
因为 structured streaming 中目前还没有实现如下这种继承 sinker 的方式, 

```$xslt

ssStream. 
format("mysql"). 
option("jdbc","jdbc:mysql://ip:port/sparkdata")
option("driver", "com.mysql.jdbc.Driver")

```
所以我们需要自己实现 ForeachWriter 然后按照自己的逻辑来实现. 


#### 目前能想到的解决方案(alternatives) 
* 目前想到的就是, 在 sinker 提交写入数据的时候, 通过事务 + 回滚的方式来将控制数据重复的逻辑放到 MySQL 端, 通过重复数据提交会通过回滚的方式来拒绝来避免数据的重复提交. 
  即, 
  ```
  1. 根据数据记录数值生成全数据表范围内唯一 ID, UUID = s"${MysqlData.id}_${MysqlData.msg}_${MysqlData.timestamp}
  2. 将该 UUID 字段对应为 MySQL 数据表中的主键
  3. 开启事务来执行 insert/update 操作, 将处理主键重复的 exception 分支中添加回滚逻辑
  ```
* 其实, 完全也可以通过这种方法: 在数据表中增加一个冗余字段, 然后将唯一的 UUID 写对应这个冗余字段, 在每次提交数据之前, 加上事务操作(其实就是为数据库的写入过程加个锁)
  在事务处理阶段,拿着当前生成的 UUID 到数据库中执行 select 操作, 如果数据库表中的冗余字段属性已经存在相同的 UUID 数值, 则放弃此次提交,
  如果没有相同的 UUDI, 那么执行数据写入, 然后整个事务提交
     
#### 推送数据 schema 说明

* kafka 端推送数据格式
```$xslt
{
  id:String,      # kafka key 用于 kafka 自定义散列分区
  msg:String,     # 消息体
  timstamp:String # yyyymmddHHMMSS
}
```

* Structured Streaming Spark App 中处理数据格式:DataStream[ResultData]
```$xslt
{
 id:String, # ${ResultData.id}-${ResultData.timestamp}
 msg:Stirng,
 timestamp:Stirng # yyyymmddHHMMSS
}
```
* mysql 数据表 db:spark table: data1/data2 两个数据表的 schema 格式相同, 
* data1 作为普通 mysql sink 数据源, data2 作为事务 mysql sink 数据源
```$xslt
{ 
  id:Stirng , # primary key not auto-incremental && not auto-generated 
  msg:String, #
  timestamp:String
}
```

#### 具体实现步骤
3. MySQL 单元测试阶段 
* 代码实现 [DataBaseDemoTest.scala](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/test/scala/com/spark/aimer/tests/DataBaseDemoTest.scala)
* 输出结果 [output.log](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/test/logs/output.log)
> 3.1 向指定 MySQL 数据库中提交简单格式数据, 期待结果: 数据库连通没问题, 数据正常从 unit test 中写入数据表中<p/>
> 3.1 结果:正常写入数据库, 连接成功<p/>
> 3.2 验证当先后提交的两条数据的主键相同时, 后一次提交中确实会跑出异常并将异常截获, 期待结果: 后一次提交异常能够被截获<p/>
> 3.2 结果: 抛出异常, 第二个提交的记录没有成功写入, 第一条成功写入<p/>
> 3.3 开启事务提交, 同样提交两条数据主键相同的同时,后一次提交异常中执行回滚, 期待结果: 后一次提交异常被截获, 提交数据无效<p/>
> 3.3 结果: 前一次提交正常提交, 后一次提交出现异常被正常捕获后执行回滚操作<p/>
结论: 只要数据库主键字段加以设置, 最后都能保证写入 1 条数据, 无论是否加事务处理

4. Structured Streaming 推送数据
* 代码实现 [KafkaSourceToMySqlSink.scala](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/structured/sink/KafkaSourceToMySqlSink.scala)
> 4.0 在这里, 为了对问题进行重现, 我们设计上游 kafka 在进行数据生成的时候, 相同的记录条数生成 2 次, 不对记录条数进行限定 条<p/> 
> 4.1 实现普通模式,将数据从 kafka -> spark -> mysql 中进行提交, 最后检查数据库中写入数据的条数, 以及是否有重复数据<p/>
> 4.1 输出结果
```$xslt
output:
Exception is Duplicate entry '46-20181021152318' for key 'PRIMARY' 
# 虽然这里抛出异常, 但同 PRIMARY key 的 1 条数据可写入数据库, 
# 由于数据库主键的控制, 虽然没有加事务控制, 写入数据也能保证没有重复
18/10/21 15:23:22 WARN DFileSystem: Delete src not found! path: /xxx/checkpointPath/commits/.73018576-7783-4b55-8fc4-e2e67ed5e2f3.tmp
18/10/21 15:23:22 WARN ProcessingTimeExecutor: Current batch is falling behind. The trigger interval is 10 milliseconds, but spent 1491 milliseconds

db data1 表中部分数据
id                  msg         timestamp
38-20181021152254	msg content	20181021152254
39-20181021152257	msg content	20181021152257
```

> 4.2 实现事务提交模式, 整个数据流同上, 但在数据从 spark 通过 sinker 写入到 mysql 的阶段, 使用事务+回滚/事务+ 检查 的方式来控制重复数据<p/> 
> 4.2 输出结果
```$xslt
output:
18/10/21 15:40:30 WARN ProcessingTimeExecutor: Current batch is falling behind. The trigger interval is 10 milliseconds, but spent 1538 milliseconds
18/10/21 15:40:31 WARN HDFSFileSystem: Delete src not found! path: /xxx/xxx/checkpoint/offsets/.32b850a8-2895-4d48-b2bd-e5fdfdc80c8c.tmp
Exception is Duplicate entry '389-20181021154028' for key 'PRIMARY'
Exception is Duplicate entry '389-20181021154028' for key 'PRIMARY'
18/10/21 15:40:32 WARN HDFSFileSystem: Delete src not found! path: /xxx/xxxx//checkpoint/commits/.ae2db384-2854-4496-a907-8983e4afff22.tmp

db data2 表中部分数据
381-20181021154004	msg content	20181021154004
382-20181021154007	msg content	20181021154007
383-20181021154010	msg content	20181021154010
384-20181021154013	msg content	20181021154013
385-20181021154016	msg content	20181021154016
386-20181021154019	msg content	20181021154019
387-20181021154022	msg content	20181021154022
388-20181021154025	msg content	20181021154025
389-20181021154028	msg content	20181021154028
390-20181021154031	msg content	20181021154031
391-20181021154034	msg content	20181021154034
392-20181021154037	msg content	20181021154037
393-20181021154040	msg content	20181021154040
```

#### 最终结论
* 1. 只要将同步数据内容和数据库表主键适当建立映射关系, 每次在 sink 中提交数据的时候通过主键便可控制数据重复写入
* 2. 测试中尚未发现因 structurd streaming 自身处理逻辑不当这种, 因计算造成数据重复提交的问题
* 3. 在 ForeachWriter 中 事务 + 回滚 的方式和主键普通提交方式效果相同
* 4. 如果下游数据表中的主键字段无法随意改动, 可通过适当增加 sql 语句复杂度 通过创建中间数据表, 并创建中间临时表主键与数据写入表主键建立外键的关系, 通过外键来约束数据写入重复的问题,
   如果情况允许, 也可使用在数据目的表中增加冗余字段,用该冗余字段映射写入记录生成的全表唯一 ID 即,UUID, 每次写入之前通过事务将数据写入表加锁, 然后检测表中冗余字段是否存在数值重复, 
   若不重复, 写入, 事务提交
   若存在重复, 则不写入, 在这里事务起到的是加写锁的作用, 而并非用于回滚是有区别于方法 3 中事务的使用 

#### references
[Spark Streaming Crash 如何保证 Exactly Once Semantics](https://www.jianshu.com/p/885505daab29)


