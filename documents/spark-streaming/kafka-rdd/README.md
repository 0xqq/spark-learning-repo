* 个人认为这个 SPARK-4964 jira 提交的代码和文档是很值得学习一下的,
其中首次提出了数据流方面 Kafka partition 对接 RDD partition 进行数据传输的方法, 并且给出的设计文档代码各个方面都值得阅读了解一下, 虽然 spark-streaming 按照目前的发展趋势已经逐步被用户和社区'抛弃' 但是其中一些设计思想, 哪怕是为了弥补一些问题而想出的'临时解决方案'都是很值得开发者借鉴的. 
* 而 SPARK-4964 jira 的提出, 个人觉得也对 kafka 0.8 升级到 0.10.x 版本的时候将 offset 从 zk 迁移到 kafka cluster 端, 以及 streaming 中记录的 checkpoint 将 DAG query info + offset 杂糅在一起的问题升级到 structured streaming 中单独维护 offset 的问题这里也多多少少的有一定的影响. 在这个代码分支中提出的 HasOffsetRanges, OffsetRange 这两个对象也抽象了一个 RDD 数据中的 [start-offset,until-offset) 的 offfset 的记录方式. 

* 从中也可以看出社区在对数据在各个组件间传输中, 对 'exactly-once' 语义的实现不断的尝试和努力.  

* [codes](https://github.com/koeninger/spark-1/blob/kafkaRdd/external/kafka/src/main/scala/org/apache/spark/streaming/kafka/)
* [doc](https://blog.cloudera.com/blog/2017/06/offset-management-for-apache-kafka-with-apache-spark-streaming/)
* [jira](https://issues.apache.org/jira/browse/SPARK-4964)
