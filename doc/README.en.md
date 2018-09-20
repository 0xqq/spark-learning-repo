## How to re-appear the problem ?


### Update KafkaDirectDemo 

replace topic, broker, and group with your own
<p>
vi KafkaDirectDemo.scala 


val topic = "${set your kafka.topic here}"

val broker = "${set your kafka.bootstrap.servers here}"

val group = "${set your group.id here}"

</p>


### Update KafkaProducer 



### Reset your maven repo 

### Compile the repo 

### Append JVM configs to your Spark-client/conf/spark-defaults.conf 



### Start-up your kafka producer process with following commands: 


### Start-up your kafka stream spark app by executing following scripts:   



