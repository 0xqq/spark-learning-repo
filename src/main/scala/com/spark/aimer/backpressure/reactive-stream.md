API　Components 
API 构成

The API consists of the following components that are required to be provided by Reactive Stream implementations:
(Reactive Stream) 由如下 4 个 API 构成, 如果你想使用 Reactive Stream 的话,需要给出这 4 个 API 具体实现逻辑:

* Publisher 
* 发布者  
* Suscriber 
* 订阅者
* Subscription 
* 订阅消息
* Processor 
* 处理者

A Pulisher is a provider of a potentially unbounded number of sequenced elements, publishing them according to the demand received from it Subscriber(s).
发布者在这里是指潜在地无上限供应一系列元素的数据提供方, 其所发布数据元素的速率受限于其订阅者接收数据的速率.

In response to a call to Publisher.subscribe(Subscriber) the possible invocation sequences for methods on the Subscriber are given by the following protocol:
至于在 Publisher.subscribe(Subscriber) 这个函数被调用之后在 Subscrier 这端应该根据语境响应哪些方法在下面的协议中有给出:

```
onSubscribe onNext*(onError|onComplete)
```

This means that onSubscribe is always signalled, followed by a possibly unbounded number of onNext signals( are requested by Subscriber) followed by an onError signal if there is a failure, or an onComplete signal when no more elements are available -- all as long as the Subscription is not cancelled. 

这就意味着, onSubscribe 无论如何何种情况都会被调用到, 随着 onSubscribe 被调用其 onNext 这个函数也会被调用多次(随 Subscriber 被请求的次数而定), 在 onNext 被调用之后随之而来的是如果出现错误的话则 onError 方法会被调用, 如果没有数据被接收的话那么 onComplete 这个方法将会被调用. (这里很奇怪诶, 因为信号量对应的仅仅是一个变量, 但是在这里结合语境这里的 signal 完全就是和函数一样,这里看了后来的 glossary/专有名词这里会有相关介绍,其实就是 method) -- (上述函数函数会被轮番调用)只要是 Subscriber 这边不去掉它的订阅操作的话. 

NOTES
注意
* The specifications below use binding words in capital letters from https://www.ietf.org/rfc/rfc2119.txt 
* 下面将要介绍的规范中所使用的关联词的大写字母参考自这里

### Glossary 
### 词汇列表

| Term | Definition | 
|术语|相关定义|
| ------ | ------ | 
| Signal | As a noun: on of the onSubscribe, onNext, onComplete, onError, request(n) or cancel methods. As a verb: calling/invoking a signal |  
| 信号(量)|(结合语境)如果是名词的话,例如 onSubscribe, onNext, onComplete, onError, request(n) 或是 cancel 这些语境中指的是方法(函数调用)；（结合语境）如果是动词的话: 指的是所 调用/触发信号(这个动作) |
|Demand|As a noun, the aggregated number of elements requested by a Subscriber which is yet to be delivered (fulfilled) by the Publisher. As a verb, the act of request-ing more elements|
|需求|如果是名词,便是指有订阅方发起的将数据元素聚合的过程,这个订阅方已经被分配了数据发布者;如果是动词的话,则是指要求更多数据元素这一请求操作. |
|Synchronous(ly)|Executes on the calling Thread.|
|同步(地)|在调用线程上执行|
|Return normally| On ever returns a value of the declared type to the caller. The only leagal way to signal failure to a Subscriber is via the onError method.|
|合法返回形式|在调用方调用之后满足声明类型的返回值. 例如对于 Subscriber/订阅者来说, 在调用其触发方法失败之后, 其唯一合法的返回形式便是调用 onError 方法来传达.|
|Responsivity|Readiness/ability to respond. In this document used to indicate that the different components should not impair each others ability to respond.|
|响应率|响应的反映速率/响应能力. 在本篇文档中, 是用来专指不同的方法组合起来使用不会相互削弱影响彼此的相应速率. |
|Non-obstructing|Quality describing a method which is a quick to execute as possible -- on the calling thread. This means, for example, avoids heavy computations and other things that would stall the caller's threads of executions|
|非阻塞|用来描述在某个方法一经被线程调用时其执行有多快这样一种程度. 也就比方说吧, 之所以定义这种描述量词, 是为了避免计算过重和其他的原因导致调用者线程运行拖延的太久(就是描述线程执行计算的时候运行时间进行量化)|
|Terminal state|For a Publisher: When onComplete or onError has been signalled. For a Subscriber: When an onComplete or onError has been received.|
|终结状态|对于发布者/Publisher 来说, 当其触发了 onComplete 或是 onError 这些方法的时候. 对于订阅者/Subscriber 来说, 当其受到 onComplete 或是 onError 这种方法调用来说. 这几种情况对于 Publisher/Subscriber 来说就迎来了其终态.|
|NOP|Execution that has no detectable effect to the calling thread, and can as such safely be called any number of times.|
|NOP|(这个描述有点像幂等的概念)NOP 指的是这样一种执行操作: 这个操作执行之后对执行该操作的线程而言没有任何影响(什么叫有影响,如果线程有一个多个线程共享的公共变量,或者是共享空间,执行一次之后,因这个公共的对象数值被修改了,导致下个执行相同方法的线程调用执行的逻辑和这个不一样,这个就叫做有影响),且执行几次都是没有影响的,那这个操作方法便可称之为 NOP 的. |
|External synchronization| Access coordination for thread safety purposes implemented outside the constructs defined in this specification, using techiniques such as , but not limited to , atomics, monitors, or locks.|
|外部同步操作|借助于一些方法, 例如原子变量, 监控, 或是锁的方式来实现线程安全地来协调线程访问的顺序|
|Thread-safe|Can be safely invoked synchronously, or asynchronously, without requiring external synchronization to ensure program correctness. |
|线程安全|(函数)能够以安全同步地方式调用，或是异步下不借助于外部同步方式来确保程序正确地运行.|




### SPECIFICATION
### 规范

* Publisher
* 发布者

```
public interface Publisher<T>{
    public void subscribe(Subscriber< ? super T> s)
}
```

| ID | Rule | 
|ID|规范|
| ------ | ------ | 
|1| The total number of onNext's signalled by a Publisher to a Subscriber MUST be less than or equal to the total number of elements requestes by that Subscriber's Subscription at all times.|
|1|(onNext 这个方法是 Publisher 端发送下一条消息的方法调用)Publisher 端的 onNext 这个信号方法调用次数必须 <= Subscriber 端请求数据的次数|
|bulb|The intent of this rule is to make it clear that Publishers cannot signal more elements than Subscribers have requested. There's an implicit, but important, consequent to this rule: Since demand can only be fulfilled after it has been received, there's a happens-before relationship between requesting elements and receiving elements.|
|灯泡|这个约束是为了明确这样一件事情: 发布者所发布的消息条数不可以大于接受者请求的条数. 有这样一个不成文但是十分重要的有关规定: 因为请求只有在其被接收到之后才能被响应, 所以在请求数据元素和接收数据元素二者之间有一个先序关系的(也就是说一旦这个 rule 不遵守的话,会破坏先序关系, 会导致整个系统语义出现理论上的错误.)|
|2|A Publisher MAY signal fewer onNext than requested and terminate the Subcription by calling onComplete or onError|
|2|数据发布方调用onNext(也就是调用发送数据方法的次数)的次数可以 < 数据订阅方请求的次数, 并且数据发布方可以通过调用 onComplete 或是 onError 来主动终结订阅.|
|bulb|The intent of this rule is to make it clear that a Publisher cannot guarantee that it will be able to produce the number of elements requested; it simply might not be able to produce them all ; it may be in a failed state; it may be empty or otherwise already completed.|
|灯泡|这个约束的意图在于说明数据发布端无法保证其发送的消息条数一定要达到请求的消息条数,如果上游没这么多消息的话没法发送请求的消息条数是可以的,(当上游无法构建这么多消息条数的时候)可以以失败,空,或是完成状态来结束订阅.
|3|onSubscribe, onNext, onError and onComplete signaled to a Subscriber MUST be signalled  in a thread-safe manner -- and if performed by multiple threads -- use external synchronization|
|3|在数据订阅方/Subscriber 的 onSubscribe , onNext, onError 和 onComplete 这些调用必须以线程安全的方式来调用, 如果真的要设计到多线程调用的话, 必须借助于外部同步方法来保证其线程安全.|
|bulb|The intent of this rule is to make it clear that external synchronization must be employed if the Publisher intents to send singals from multiple/different threads.|
|灯泡|这个约束/规则的意图在于确保如果数据发布方在通过多线程或是这不同线程推送数据给数据订阅方时,数据订阅方可借助外部同步方法确保线程的安全|
|4|If a Publisher failed it MUST singal an onError|
|4|数据发布方一旦执行错误必须通过调用 onError 方法来传递失败的信号|
|bulb|The intent of this rule is to make it clear that a Publisher is responsible for notifying its Subscribers if it detects that it can not proceed - Subscribers must be given a chance to clean up resources or otherwise deal with the Publisher's failures.|
|灯泡|这个规范的目的是为了确保:数据发布方遇到异常的时候,是由数据发布方发现其遇到错误无法运行的时候,是尤其来负责将失败消息通知给其数据订阅方, 通过这种方式数据订阅方会在接收到失败通知消息的时候进行资源清理或者做一些其他的应对失败的处理.|
|5|If a Publisher terminates successfully(finite stream) it MUST signal an onComplete|
|5|当 Publisher 成功结束的时候(数据流是有限的情况下)它必须通过调用 onComplete 将结束的信号传递给 Subscriber|
|bulb|The intent of this rul is to make it clear that a Publisher is responsible for notifying its Subscribers that it has reached a terminal-state -- Subscribers can then act on this information; clean up resources, etc|
|灯泡|这个规定的目的是为了保证: Publisher 有义务通知订阅其数据的 Subscribers 它已经达到终态 -- 这样 Subscribers 在接收到这个信息之后能够做一些资源回收清理或者是其他收尾工作|
|6|If a Publisher signals either onError or onComplete on a Subscriber, that Subscriber's Subscription MUST be considered cancelled.|
|6|如果 Publisher 通过调用 onError 或是 onComplete 任意一种方法来向 Subscriber 传递信号的话, 则认为 Subscriber 向 Publisher 所发起订阅必须被取消了. |
|bulb|The intent of this rule is to make sure that a Subscription is treated the same no matter if it was cancelled, the Publisher signalled onError or onComplete.|
|灯泡|这个规定的目的是为了说明: 无论 Publisher 通过 onError 还是 onComplete 发送信号, 创建的订阅会被取消.|
|7|Once a terminal state has been signalled(onError, onComplete) it is REQUIRED that no further signals occur.|
|7| 一旦被传达了终态信号(通过 onError 或是 onComplete 方法传达的)就不允许继续再继续传递其他的信号信息了|
|bulb|The intent of this rule is to make sure that onError and onComplete are the final states of an interaction between a Publisher and Subscriber pair.|
|灯泡|这个规定是为了说明, onError 和 onComplete 这两个调用方法是作为传递 Publisher 和 Subscriber 这个订阅对的终态信号量的方法,一经调用整个订阅交互的过程将会结束掉|
|8|If a Subscription is cancelled its Subscriber MUST eventually stop being singlled.|
|8|一旦一个订阅过程终止了, 那么在这个订阅对中的订阅一方必须停止向数据发送方发送请求数据的信号信息|
|bulb|The intent of this rule is to make sure that Publishers respect a Subscriber's request to cancel a Subscription when Subscription.cancle() has been called. The reason for eventually is because signals can have propagation delay due to being asynchronous.|
|灯泡|这个规则的意图是确保Publisher 应该遵照 Subscriber 方停通过 Subscription.cancle() 来停止数据订阅的意图. 之所以这么规定是因为传递的信号信息有时候会因为消息的异步而存在一定概率的迟延.|
|9|Publisher.subscribe MUST call onSubscribe on the provided Subscriber prior to any other signals to that Subscriber and MUST return normally, except when the provided Subscriber is null in which case it MUST throw a java.lang.NullPointerException to the caller, for all other situatons the only legal way to signal failure(or reject the Subscriber) is by calling onError (after calling onSubscribe).|
|9||











