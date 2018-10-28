### Environments request 
* Spark [2.1, 2.3)
* Maven [3.3.9]
* Kafka [0.10,0.11]_2.11
* JDK [1.8]

### How to invoke kafka to generate upstreaming data 

* step1: create a topic in kafka cluster and updating the schema of the kafka producer, for now, kafka producing data in schema like this 
```
{
 "id":String,
 "msg":String,
 "timestamp":String 
}
```
* step2: modify the <b>brokers</b> and <b>topic</b> in [Producer](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/kafka/Producer.scala) 
* step3: compile this project by executing ```mvn clean install -DskipTests=true``` (some unit tests are added, you have to skip unit tests by adding this maven option -DskipTests=true)
* step4: execute commands ```java -cp ./target/learn-spark-jar-with-dependencies.jar  com.spark.aimer.kafka.Producer``` to let kafka producing data  
* step5: part of the data produced by Producer 
```
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

### [KafkaSourceToHdfsSink](https://github.com/Kylin1027/spark-learning-repo/blob/master/src/main/scala/com/spark/aimer/structured/sink/KafkaSourceToHdfsSink.scala)
* step1: modify <b>topic</b> and <b>broker</b> 
* step2: compile the project by executing ```mvn clean install -DskipTests```
* step3: execute ```./bin/spark-submit --master spark://ip:port --class com.spark.aimer.structured.sink.KafkaSourceToHdfsSink ./xxx.jar ``` to run the spark application

### [KafkaSourceToKafkaSink]()
* step1: create a new topic to receive output results from spark application 
* step1: modify <b>source-topic, sink-topic, broker</b> in KafkaSourceToKafkaSink 
* step2: compile the project by commands ```mvn clean install -DskipTests```
* step3: execute spark submitting commands like ```./bin/spark-submit --master {local[*]|spark://ip:port|yarn-cluster|yarn-client} ./compiled-jar.jar``` to submit your application

### [KafkaSourceToMySqlSink]()
* step1: modify <b>topic, brokername</b> in KafkaSourceToMySqlSink 
* step2: creating a table in mysql, referring to this sql command 
``` 
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
* step3: execute spark submitting commands like ```./bin/spark-submit --master {local[*]|spark://ip:port|yarn-cluster|yarn-client} ./compiled-jar.jar``` to submit your application to local,standalone or yarn cluster 
* NOTE: in mysql sink demo, we tried two ways to sink data from spark to mysql in case of duplicating data insertion, and both of them can work
* > method1: we use the id as the primary key in mysql table, and make sure that the id in kafka producer is unique, because RMDB doesn't allowed two records which with the same primary key insert to one table, 
  so only one of the records which with the same id/primary-key-value is allowed to sink to mysql table 
* > method2: we use the transaction && rollback these mysql database features to prevent mysql sinker to receiving duplicated data records 
