package org.apache.spark 

import java.io.Serializer 
import java.util.Properties 

import org.apache.spark.annotation.DeveloperApi 
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.memory.TaskMemoryManager 
import org.apache.spark.metrics.source.Source 
import org.apache.spark.shuffle.FetchFailedException 
import org.apache.spark.util.{AccumulatorV2, TaskCompletionListener, TaskFailureListener}

/**
 这个 TaskContext 其实就是 Spark 中所抽象的, 处理 1 个 partition 中数据的 Task 的对象实例.
 即, Task + Partition + 上下文环境 = TaskContext ,
 1 Task = 1 Partition = 1 TaskContext , 而至于这个 Task 是否会被轮到资源, 和线程池的调度有关系. 
*/
object TaskContext {

    /**
        Return the currently active TaskContext. This can be called inside of user 
        functions to access contextual information about running tasks.  
    */ 
    /**
        get 方法返回处于活跃状态的 TaskContext 的实例对象. 通过 get 方法调用者可以在其自己的方法中
        获取到当前正在运行的 task 的内部信息.
    */
    def get(): TaskContext = taskContext.get 


    /**
       Returns the partiton id of currently active TaskContext. It will return 0
       if there is no active TaskContext for cases like local execution. 
    */
    /**
      返回当前处于活跃状态的 TaskContext 正在处理的 Partition 的 partitionId.
      如果当前系统中没有处于活跃状态的 TaskContext 实例, 例如本次模式运行任务的时:
      '--master local[*]' 这种, 整个 SparkContext 中便不会存在处于活跃状态的 TaskContext. 
    */
    def getPartitionId():Int = {
    	val tc = taskContext.get()
    	if ( tc eq null) {
    		0
    	} else {
    		tc.partitionId()
    	}
    }

    // 这个就是 task 线程池, 而 TaskContext 是直接接受调度的对象
    private[this] val taskContext:ThreadLocal[TaskContext] = new ThreadLocal[TaskContext] 

    
    // Note: protected[spark] instread of private[spark] to prevent the following two from
    // showing up in JavaDoc 
    // 注意: 下面的这两个方法我们使用 protected[spark] 而不是 private[spark] 目的是防止在生成的 JavaDoc 中
    // 出现这两个方法的 API, 因为这两个方法是 Spark 内部方法, 不能让用户调用

    /**
      Set the thread local TaskContext. Internal to Spark.  
      Spark 内部函数, 将 TaskContext 实例加入到线程池的队列中, 进行排队等待调度.
    */ 
    protected[spark] def setTaskContext(tc:TaskContext):Unit = taskContext.set(tc)

    /**
      Unset the thread local TaskContext. Internal to Spark. 
      Spark 内部函数, 将 TaskContext 实例从线程池队列中, 移除. 
    */
    protected[spark] def unset():Unit = taskContext.remove()

    /**
     An empty task context that does not represent an actual task. This is only used in tests. 
     供测试使用的返回 1 个空 TaskContext 实例对象的方法.
    */ 
    private[spark] def empty():TaskContextImpl = {
    	new TaskContextImpl(0, 0, 0, 0, 0, null, new Properties, null)
    }
}

/**
  Contextual information about a task which can be read or mutated during execution.
  To access the TaskContext for a running task, use: 
  {{{
	org.apache.spark.TaskContext.get() 
  }}}
*/
/**
  TaskContext 类中描述了在运行期间的支持访问或是内部信息修改的 task 的上下文信息. 
  如果想访问处于运行状态的 task 的话, 使用如下的方法来获取这个 TaskContext 实例对象的引用
  {{{
	org.apache.spark.TaskContext.get() 
  }}}
*/
abstract class TaskContext extends Serializable {
	// Note: TaskContext must NOT define a get method. Otherwise it will prevent the Scala 
	// compiler from generating a static get method ( based on the companion object's get method).
	// 注意: TaskContext 类中千万别定义 get 方法. 不然的话 Scala 编译器会把我们之前在
	// TaskContext object 中定义的 get 静态方法给置无效, 从而导致静态 get 方法无法使用. 

	// Note: Update JavaTaskContextCmpileCheck when new methods are added to this class.
	// 注意: 当往这个类中增加新方法的时候, 注意新方法也要同步加到 JavaTaskContextCompileCheck 这个类中

	// Note: getters in this class are defined with parentheses to maintain backward compatibility.
	// 注意: TaskContext 类中的 getters 使用括号来定义, 目的是为了保持向后的兼容性.  

	// Returns true if the task has completed 
	// 如果当前 TaskContext 运行结束, 则返回 true 
	def isCompleted():Boolean 

	// Returns true if the task has been killed
	// 如果运行 TaskContext 的线程被 kill 则返回 true 
	def isInterrupted():Boolean 

	// Returns true if the task is running locally in the driver program.
	// @return false 
	// 如果 TaskContext 用的是 driver 中线程资源运行的话, 返回 true
	@deprecated("Local execution was removed, so this always returns false", "2.0.0")
	// 但是从 Spark 2.0.0 版本开始使用 driver 运行 TaskContext 这种本地运行模式已经被移除了, 所以无论何时访问返回均为 false
    def isRunningLocally():Boolean 

    // Adds a (Java friendly) listener to be executed on task completion.
    // This will be called in all situations - success, failure, or cancellation. 
    // Adding a listener to an already completed task will result in that listener being called immediately.
    // An example use is for HadoopRDD to register a callback to close the input stream. 
    // Exceptions thrown by the listener will result in failure of the task. 
    // ----
    // addTaskCompletionListener 这个函数可以为 TaskContext 实例对象加一个 (Java API 友好型/ 也就是 Java 语法书写规则支持在 Java 语境下使用) 的监听器.
    // 加了这个监听器之后, 无论任何时候只要是所监听的 TaskContext 运行结束, 就会被触发调用
    // 由用户实现的这个 TaskCompletionListener 接口中定义的方法逻辑, 无论 TaskContext 是以何种方式执行结束的
    // 成功执行结束, 失败异常结束, 或是 TaskContext 执行的任务中途取消, 都会触发 listener 
    // 如果将这个 listener 注册到已经执行结束的 TaskContext实例上的结果便是, listener 监听器一注测, 监听器中定义的回调方法便会立即执行 

    // 一个应用案例是, 在 HadoopRDD 中通过这个 addTaskCompletionListener 函数
    // 而注册的回调方法的逻辑是关闭与输入流的连接. 
    // 而在 listener 中所定义的回调方法所抛出的异常信息会将整个计算任务/TaskContext 的状态切换到失败
    def addTaskCompletionListener(listener:TaskCompletionListner):TaskContext 

    // Adds a listener in the form of Scala closure to be executed on task completion.
    // This will be called in all situations - success, failure, or cancellation. 
    // Adding a listener to an already completed task will result in that listener being called immediately. 
    // An example use is for HadoopRDD to register a callback to close the input stream. 
    // Exceptions thrown by the listener will result in failure of the task. s
    // 这个方法定义使用方法同上, 不过这个方法适用于 Scala 语法规则, 也就是在 Scala 语境下使用的
    // 而使用的方法便是, 用户自定义实现逻辑, 确保输入参数为 TaskContext 实例, 无返回结果 即可
    def addTaskCompletionListener(f:(TaskContext) => Unit):TaskContext = {
    	addTaskCompletionListener(new TaskCompletionListener {
            override def onTaskCompletion(context:TaskContext):Unit = f(context)
    	})
    }

    // Adds a listener to be executed on task failure. Adding a listener to an already failed task
    // will result in that listener being called immediately.
    // 下面这个方法会将监听器/listener 监听将会失败的计算任务/TaskContext 上. 如果将监听器/listener 监听状态已经是
    // 失败状态的 task 上的话, 会导致监听器中的回调函数立即触发执行. 
    def addTaskFailureListener(listener:TaskFailureListener):TaskContext 

    // Adds a listener to be executed on task failure. Adding a listener to an already failed task 
    // will result in that listener being called immediately. 
    // 同上, 这个是 Scala 开发语境版本的, 用户可以将自定义逻辑写到参数类型为 TaskContext, Throwable 返回值 Unit 自定义函数中
    // 传入方法即可
    def addTaskFailureListener(f:(TaskContext, Throwable)=> Unit):TaskContext = {
        addTaskFailureListener(new TaskFailureListener {
          override def onTaskFailure(context:TaskContext, error:Throwable):Unit = f(context, error)
        })
    }

    // The ID of the stage that this task belong to 
    // 这个函数返回的是, 在 DAGScheduler 中调度 Task 也就是 TaskContext 时, 这个 Task 会划归到哪个 Stage 中. 
    def stageId():Int 

    // How many times the stage that this task belongs to has been attempted. 
    // The first stage attempt will be assigned stageAttemptNumber = 0, and subsequent attempts will have increasing attempt numbers.
    // 如下的这个方法会返回当前这个 TaskContext 所归属的 Stage 目前重试的次数.
    // 首次重试会将计数器的 stageAttemptNumber 数值置为 0， 接下来 Stage 每重试一次便会增加一次计数数值. 
    // (Stage 重试次数是有其阈值上限的, 如果失败达到阈值的话, 整个 Stage 中的 TaskContext 便都会被认定失败)
    def stageAttemptNumber():Int  

    // The ID of the RDD partition that is computed by this task 
    // 如下这个方法所返回的是, 当前这个 TaskContext 所处理计算的是 RDD 中的哪一块分区, 通过 partition ID 号来标识这个 Partition 
    // 而这个 partition ID 是 RDD 中 getPartition 中使用 zipWithIndex 遍历 Array 生成的 0 - Array.length -1 这个数值范围的
    def partitionId():Int 

    // How many times this task has been attempted. The first task attempt will be assigned attemptNumber = 0 ,
    // and subsequent attempts will have increasing attempt numbers. 
    // 这个方法用来返回 TaskContext 被重试的次数. 首次重试的时候 attemptNumber 数值会被置为 0 ,
    // 在接下来的执行中, 每次重试 1 次, attemptNumber 加 1 (直到数值达到阈值后, TaskContext 状态会变为失败)
    def attemptNumber():Int 

    // An ID that is unique to this task attempt (within the same SparkContext, no two task attempts will share
    // the same attempt ID). This is roughly equivalent to Hadoop's TaskAttemptID. 
    // 函数返回的 ID 数值和当前 TaskContext 重试次数相同(这个 ID 数值是 SparkContext 上下文环境中唯一用来表示一个 TaskContext 重试次数的数值,
    // 在同一个 SparkContext 环境中不会出现 2 个 task 的重试 ID 是相同的情况). 这个变量可以等价看做是 Hadoop 中的 TaskAttemtpID . 
    def taskAttemptID():Long 

    // Get a local property set upstream in the driver, or null if it is missing. See also `org.apache.spark.SparkContext.setLocalProperty`
    // 这个方法根据传入的参数名称返回设置在本地上 driver 中的配置项信息, 如果没有设置返回 null. 具体的配置项都有哪些, 看
    // `org.apache.spark.SparkContext.setLocalProperty` 这个类.  
    def getLocalProperty(key:String):String 


    // 返回 TaskMetrics 
    @DeveloperApi 
    def taskMetrics():TaskMetrics 

    // :: Developer Api :: 
    // Returns all metris sources with the given name which are associated with the instance
    // which runs the task. For more information see `org.apache.spark.metrics.MetricSystem`.
    // 根据与运行计算任务相关的实例的所有系统参数采集数据源. 详细信息请看 `org.apache.spark.metrics.MetricsSystem` .
    @DeveloperApi 
    def getMetricsSources(sourceName:String):Seq[Source]

    // If the task is interrupted, throws TaskKilledException with the reason for the interrupt.
    // 如果计算任务/TaskContext 运行被中断(其实就是被 kill 了), 会抛出 TaskKilledException 这个异常实例, 在实例中给出具体的中断信息. 
    private[spark] def killTaskInterrupted():Unit 

    // If the task is interrupted, the reason this task was killed, otherwise None. 
    // 如果计算任务/TaskContext 计算中断的话, 如果是 task 是被 kill 中断的, 返回详细描述信息, 否则其他原因返回 None 
    private[spark] def getKillReason():Option[String]

    // Returns the manager for this task's managed memory.
    // 返回这个 task 的 TaskMemoryManager 内存管理实例对象
    private[spark] def taskMemoryManager():TaskMemoryManager 

    // Register an accumulator that belongs to this task. Accumulators must call this method when deserializing in executors. 
    // 注册一个和属于该计算任务的累加器, 当累加器在 executor 中执行反序列化操作的时候, 累加器必须调用如下这个函数.
    private[spark] def registerAccumuator(a:AccumulatorV2[_, _]):Unit 
    
    // Record that this task has failed due to a fetch failure from a remote host. This allows 
    // fetch-failure handling to get triggered by the driver, regardless of intervening user-code
    // 这个函数会记录当前的计算任务/Task or TaskContext 是因为从远程计算节点拉取数据失败而导致的整个计算任务的失败.
    // 这样一来数据拉取失败的后续收尾工作会交付给 driver 来处理, 而将用户代码无需考虑对这种类型的异常进行处理.
    private[spark] def setFetchFailed(fetchFailed:FetchFailedException):Unit 

}