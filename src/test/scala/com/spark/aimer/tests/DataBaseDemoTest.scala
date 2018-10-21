package com.spark.aimer.tests

import java.sql._

import org.scalatest.FunSuite

class DBOperationSuite extends FunSuite {


   test("[suite] insert one record to mysql") {
    val url:String = "jdbc:mysql://ip:port/spark"
    val username:String = "dbproxy"
    val passwd:String = "****"
    val driver = "com.mysql.jdbc.Driver"
    Class.forName(driver)
    val dbConnHandler = DriverManager.getConnection(url, username, passwd)
    val statement:Statement = dbConnHandler.createStatement
    val sql:String = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('1', 'msg content', '201810201000')"
    try {
      statement.execute(sql)
    } catch {
      case ex:Exception => {
        println(s"Exception is ${ex.getCause}")
      }
    } finally {
      statement.close()
    }

  }

   test("[suite] insert two same records to mysql") {
    val url:String = "jdbc:mysql://ip:port/spark"
    val username:String = "dbproxy"
    val passwd:String = "*****"
    val driver = "com.mysql.jdbc.Driver"
    Class.forName(driver)
    val dbConnHandler = DriverManager.getConnection(url, username, passwd)
    val statement1:Statement = dbConnHandler.createStatement
    val statement2:Statement = dbConnHandler.createStatement

    val insertSQL1:String = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('2', 'msg content', '201810201000')"
    val insertSQL2:String = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('2', 'msg content', '201810201000')"

    try {
      statement1.execute(insertSQL1)
      statement2.execute(insertSQL2)
    } catch {
      case ex:Exception => {
        println(s"Exception is ${ex.getCause}")
      }
    } finally {
      statement1.close()
      statement2.close()
    }
  }

    test("[suite] insert two same records to mysql by transaction method") {
    val url:String = "jdbc:mysql://ip:port/spark"
    val username:String = "dbproxy"
    val passwd:String = "***"
    val driver = "com.mysql.jdbc.Driver"
    Class.forName(driver)
    val dbConnHandler = DriverManager.getConnection(url, username, passwd)

    val insertSQL:String = s"INSERT INTO spark.data1 (id, msg, `timestamp`) VALUES('3', 'msg content', '201810201000')"


    println(s"1. SQL=${insertSQL} begin ")
    try {
      dbConnHandler.setAutoCommit(false)
      val statement:Statement = dbConnHandler.createStatement
      statement.execute(insertSQL)
      dbConnHandler.commit()
    } catch {
      case ex:Exception => {
        println(s"SQL 1 Exception is ${ex.getCause}")
        println("SQL 2 begin roll back")
        dbConnHandler.rollback()
      }
    }
    println(s"1. SQL=${insertSQL} done")

    println(s"2. SQL=${insertSQL} begin ")
    try {
      dbConnHandler.setAutoCommit(false)
      val statement:Statement = dbConnHandler.createStatement
      statement.execute(insertSQL)
      dbConnHandler.commit()
    } catch {
      case ex:Exception => {
        println(s"SQL 2 Exception is ${ex.printStackTrace()}")
        println("SQL 2 begin roll back ")
        dbConnHandler.rollback()
      }
    }
    println(s"2. SQL=${insertSQL} done")
  }




}
