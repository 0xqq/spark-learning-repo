package com.spark.aimer.util.db

import java.sql._

import org.junit.Before
import org.scalatest.FunSuite

class DBOperationSuite extends FunSuite {

  var dbConnHandler:Connection = _

  @Before
  def initDbConn () = {

    val url:String = "jdbc:mysql://10.99.202.205:8998/sparkdata"
    val username:String = "root"
    val passwd:String = "32167"
    val driver = "com.mysql.jdbc.Driver"
    Class.forName(driver)
    dbConnHandler = DriverManager.getConnection(url, username, passwd)
  }

  test("[suite] insert one record to mysql") {
   val statement:Statement = dbConnHandler.createStatement
    val sql:String = ""
    statement.execute(sql)
    statement.closeOnCompletion()
  }

  test("[suite] insert two records which primary key values are the same "){
    val statement1:Statement = dbConnHandler.createStatement
    val statement2:Statement = dbConnHandler.createStatement

    val insertSQL1:String = ""
    val insertSQL2:String = ""
    statement1.execute(insertSQL1)
  }

  test("[suite] insert two records which primary key value are the same in a transaction ") {

  }




}
