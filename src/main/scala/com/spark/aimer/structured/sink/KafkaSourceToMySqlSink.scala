package com.spark.aimer.structured.sink

import java.sql.{Connection, DriverManager}

import org.apache.spark.sql.ForeachWriter

/**
  * 本 App 主要测试将数据流从 kafka 中加载进行简单过滤
  * 5秒钟 通过 Trigger 调用计算, 然后将计算结果
  * sink 到 mysql 中,
  * 其中不考虑 RDD/Dataset 等复杂业务处理场景, 且仅接收数据流为单 kafka topic,
  *
  * 同时,考虑到使用 foreach-writer 来实现将数据从 spark 写入到 mysql 的时候
  * 数据出现重复的问题, 分别实现两个 sinker
  * 1. 普通数据提交, 在提交数据之前查询数据库中的 key 是否存在, 如果不存在执行数据写入, 存在则 pass
  * 2. 支持事务的数据提交, 根据字段生成全局唯一 -> 将全局唯一数值作为字段主键, 进行数据写入数据库的操作,
  *    在写入数据库的过程中通过事务来完成, 如果该主键已经存在, 则会出现写入失败事务回滚,
  *    通过这种方式来避免数据通过 foreach(ForeachWriter[T]) 造成的数据重复的问题
  *
  * 首先需要通过普通数据提交的 sinker 来将数据重复的问题进行复现
  * */
object KafkaSourceToMySqlSink {

}

case class ResultData(id:String, msg:String, timestamp:String)

class MySQLTransactionSinker(url:String, username:String, password:String) extends ForeachWriter[ResultData]{
  override def open(partitionId: Long, version: Long): Boolean = ???

  override def process(value: ResultData): Unit = ???

  override def close(errorOrNull: Throwable): Unit = ???
}

class MySQLOrdinarySinker(url:String, username:String, password:String) extends ForeachWriter[ResultData] {

  var conn:Connection = _

  override def open(partitionId: Long, version: Long): Boolean = {

    Class.forName("com.mysql.jdbc.Driver")
    conn = DriverManager.getConnection(url)
    true
  }

  override def process(value: ResultData): Unit = {
    // transfer ResultData to sql
    val sql = ""
    conn.setAutoCommit(false)



  }

  override def close(errorOrNull: Throwable): Unit = ???
}