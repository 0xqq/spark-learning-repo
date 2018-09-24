package com.spark.aimer.debug.compare

import java.sql.Timestamp

import org.apache.spark.sql.{DataFrame, SQLContext, SparkSession}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{Milliseconds, Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}

/**
  * Created by Aimer1027 on 2018/9/18.
  *
  * In this class(object) we will re-appear this grouping by SQL semantics :
  * select key-1, key-2, key-3, key-4 from (
  *   select *, row_number() over (partition by key-1, key-2 order by key-4 desc) as ranking from ${temp_view_name}
  * ) where ranking = 1 ;
  *
  *
  * Let's analyse deeply into this Spark-SQL semantics ：
  *
  * 1. Suppose we have a table(DataFrame) which with columns key-1, key-2, key-3, key-4
  *    then, we create a temp view over it with the name of ${temp_view_name}
  *
  * 2. And we execute an inner SQL:
  *    select *, row_number() over (partition by key-1, key-2 order by key-4 desc) as ranking from ${temp_view_name}
  *
  *    In step2
  *    2.1 We first partition the ${temp_view_name} into groups in which the value of ${key-1} ${key-2} should be the same
  *
  *    2.2 And traverse each group sorting the items by the ${key-4} and add a field with the name of 'ranking'
  *       in which will store the order of each item after soring by item.${key-4} in descending.
  *       Note: the ranking value is local scope in each group in which the ${key-1} and ${key-2} should be the same,
  *             not the values in global scope in ${temp_view_name}.
  *
  *    2.3 Then we select all columns and the ranking value from the grouping result by select * , row_number.
  *        After doing this inner SQL command, we will get
  *            -------------------------------------------
  * group-1    | key-1 | key-2 | key-3 | key-4  | ranking |
  *            ------------------------------------------
  *            | a      | b    | xxx   | 201810 |   1     |
  *            --------------------------------------------
  *            | a      | b    | xxx   | 201809 |   2     |
  *            --------------------------------------------
  *
  *            ------------------------------------------
  * group-2    | key-1 | key-2 | key-3 | key-4 | ranking |
  *            ------------------------------------------
  *            | a     |  c   |  xxxx  | 201808 | 1      |
  *            -------------------------------------------
  *            | a     |  c    | xxx   | 201807 | 2      |
  *            -------------------------------------------
  *
  *            ------------------------------------------
  * group-3    | key-1 | key-2 | key-3 | key-4 | ranking |
  *            -------------------------------------------
  *            | e     |  c   |  xxxx  | 201812 | 1      |
  *            -------------------------------------------
  *            | e     |  c    | xxx   | 201806 | 2      |
  *            -------------------------------------------
  *
  * 3. Then, we will execute the outer one:
  *    select key-1, key-2, key-3 key-4 from (
  *
  *          ${ the inner SQL command}
  *
  *    ) where ranking = 1 ;
  *
  *    So we will get the result
  *
  *             -------------------------------------------
  *  group-1    | key-1 | key-2 | key-3 | key-4  | ranking |
  *             ------------------------------------------
  *             | a      | b    | xxx   | 201810 |   1     |
  *             --------------------------------------------
  *
  *             ------------------------------------------
  *  group-2    | key-1 | key-2 | key-3 | key-4 | ranking |
  *             ------------------------------------------
  *             | a     |  c   |  xxxx  | 201808 | 1      |
  *             -------------------------------------------
  *
  *
  *             ------------------------------------------
  *  group-3    | key-1 | key-2 | key-3 | key-4 | ranking |
  *             -------------------------------------------
  *             | e     |  c   |  xxxx  | 201812 | 1      |
  *             -------------------------------------------
  *
  *
  *
  *
  *  In this implementation I will use the csv as upstream input stream instead of kafka streaming which in which
  *  you have to handle the kafka producer and extracting the data contents from the data body.
  *
  *
  *  And here is the schema of the input csv data
  *  case class MyStudentItem
  *  (
  *      gradeID:Long,
  *      classID:Long,
  *      studentID:Long,
  *      score:Int,
  *      timestamp:java.sql.Timestamp
  *  )
  *
  *  Referencing : 《Spark SQL 内核剖析》 第七章 Spark SQL 之 Aggregation
  *  Page: [108 - 111]
  *
  *  And we do not create checkpoint for convenience ~
  */


object SparkStreamingImplement {


  def csvDataParser(rdd:String) = {
    val lines = rdd.trim.split(",")
    if ( lines.length < 5 ) Nil

    val gradeID = lines(0).toLong
    val classID = lines(1).toLong
    val studentID = lines(2).toLong
    val score   = lines(3).toInt
    val timestamp = Timestamp.valueOf(lines(4).toString)

    (gradeID, classID, studentID, score, timestamp)
  }


  def main( args:Array[String] ) = {

    // here we set time interval
    val myBatchInterval:Int = 20

    val conf = new SparkConf().setAppName("SparkStreamingImpl")

    conf.set("spark.streaming.stopGracefullyOnShutdown", "true")

    conf.set("spark.streaming.unpersist", "true")


    // build SparkContext
    val sc = new SparkContext(conf)

    // build StreamingContext
    val ssc = new StreamingContext(sc, Seconds(myBatchInterval))
    ssc.remember(Milliseconds(10))

    val stuCsvStrStream:DStream[String] = ssc.textFileStream("file:///home/work/aimer/spark-2.2-x/app_streaming_structured/dataset/student.csv")

    stuCsvStrStream.foreachRDD { rdd => {
      val sqlContext = SparkSession.builder.config(rdd.sparkContext.getConf).getOrCreate()
      import sqlContext.implicits._
      val df:DataFrame = rdd.map(csvDataParser).filter(item => item == Nil).toDF("gradeID", "classID", "studentID", "score", "timestamp")
      val tempViewName = "AimerTestView"

      df.printSchema()

      df.createOrReplaceTempView(tempViewName)

      // select *, row_number() over (partition by key-1, key-2 order by key-4 desc) as ranking from ${temp_view_name}

      val innerSQL =
        s"""
          | select *, row_number() over ( partition by gradeID, classID order by timestamp desc ) as ranking from AimerTestView
        """.stripMargin


      val innerDF = sqlContext.sql(innerSQL)

      println(s"get innerDF = ${innerDF.show()}")

    }
    }

    ssc.start()
    ssc.awaitTermination()
  }
}