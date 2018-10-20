package com.spark.aimer.structured.sink

import java.sql.{Connection, DriverManager, Statement}

import org.apache.spark.SparkConf
import org.apache.spark.sql.streaming.{OutputMode, Trigger}
import org.apache.spark.sql.{DataFrame, ForeachWriter, Row, SparkSession}

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
  * docs:
  * https://github.com/Kylin1027/spark-learning-repo/tree/master/documents/structured-streaming/sinkers/mysql
  * */

case class ResultData(id:String, msg:String, timestamp:String)

object KafkaSourceToMySqlSink {

  def rddParser(rddStr:String) = {
    // TODO: add kafka streaming data parsing logic here

    val id:String = ""
    val msg:String = ""
    val timestamp:String = ""

    new ResultData(id, msg, timestamp)
  }

  def main(args:Array[String]) = {
    val conf = new SparkConf().setAppName("KafkaSourceToMySqlSink")
    val spark:SparkSession = SparkSession.builder().config(conf).getOrCreate()

    val url:String = args(1)
    val username:String = args(2)
    val password:String = args(3)

    import spark.implicits._
    // streaming reading
    val df = spark.
      readStream.
      format("kafka").
      option("kafka.bootstrap.servers", "").
      option("subscribe", "dasou-stream").
      option("startingOffsets", "latest").
      load.
      select($"value").
      as[(String)].
      map(rddParser)

    // execute query upon streaming
    val query = df.
      writeStream.
      foreach( new MySQLOrdinarySinker(url, username, password)).
      // here we can use either MySQLOrdinarySinker or MySQLTransactionSinker,
      // or we can create two queries one use ordinary sinker and another one use transaction sinker
      outputMode(OutputMode.Append).
      trigger(Trigger.ProcessingTime(1000)).
      start

    query.awaitTermination
  }

}


class MySQLTransactionSinker(url:String, username:String, password:String) extends ForeachWriter[ResultData]{
  var connection:Connection =_
  var statement:Statement = _

  override def open(partitionId: Long, version: Long): Boolean = {
    // we build connection to mysql in open method
    Class.forName("com.mysql.jdbc.Driver")
    connection = DriverManager.getConnection(url, username, password)
    true
  }

  override def process(value: ResultData): Unit = {

    val uuid:String = s"${value.id}-${value.timestamp}"

    val sqlCmd = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('${uuid}', '${value.msg}', '${value.timestamp}')"
    try {
      connection.setAutoCommit(false)
      statement = connection.createStatement
      statement.execute(sqlCmd)
      connection.commit()
    } catch {
      case ex:Exception => {
        println(s"Exception is ${ex.getMessage}")
        connection.rollback
      }
    } finally {
      statement.close()
    }
  }
  override def close(errorOrNull: Throwable): Unit = {
    connection.close
  }
}

class MySQLOrdinarySinker(url:String, username:String, password:String) extends ForeachWriter[ResultData] {
  var connection:Connection =_
  var statement:Statement = _

  override def open(partitionId: Long, version: Long): Boolean = {
    // we build connection to mysql in open method
    Class.forName("com.mysql.jdbc.Driver")
    connection = DriverManager.getConnection(url, username, password)
    statement = connection.createStatement
    true
  }

  override def process(value: ResultData): Unit = {
    val uuid:String = s"${value.id}-${value.timestamp}"
    val sqlCmd = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('${uuid}', '${value.msg}', '${value.timestamp}')"
    try {
      statement.execute(sqlCmd)
    } catch {
      case ex:Exception => {
        println(s"Exception is ${ex.getMessage}")
      }
    } finally {
      statement.close()
    }
  }
  override def close(errorOrNull: Throwable): Unit = {
    connection.close
  }
}