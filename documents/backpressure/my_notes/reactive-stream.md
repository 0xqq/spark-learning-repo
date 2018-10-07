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
|------|------------|
|术语  |  相关定义  |
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
| ------ | ------ | 
|ID|规范|
|1| The total number of onNext's signalled by a Publisher to a Subscriber MUST be less than or equal to the total number of elements requestes by that Subscriber's Subscription at all times.|
|1|(onNext 这个方法是 Publisher 端发送下一条消息的方法调用)Publisher 端的 onNext 这个信号方法调用次数必须 <= Subscriber 端请求数据的次数|
|:bulb:|The intent of this rule is to make it clear that Publishers cannot signal more elements than Subscribers have requested. There's an implicit, but important, consequent to this rule: Since demand can only be fulfilled after it has been received, there's a happens-before relationship between requesting elements and receiving elements.|
|:bulb:|这个约束是为了明确这样一件事情: 发布者所发布的消息条数不可以大于接受者请求的条数. 有这样一个不成文但是十分重要的有关规定: 因为请求只有在其被接收到之后才能被响应, 所以在请求数据元素和接收数据元素二者之间有一个先序关系的(也就是说一旦这个 rule 不遵守的话,会破坏先序关系, 会导致整个系统语义出现理论上的错误.)|
|2|A Publisher MAY signal fewer onNext than requested and terminate the Subcription by calling onComplete or onError|
|2|数据发布方调用onNext(也就是调用发送数据方法的次数)的次数可以 < 数据订阅方请求的次数, 并且数据发布方可以通过调用 onComplete 或是 onError 来主动终结订阅.|
|:bulb:|The intent of this rule is to make it clear that a Publisher cannot guarantee that it will be able to produce the number of elements requested; it simply might not be able to produce them all ; it may be in a failed state; it may be empty or otherwise already completed.|
|:bulb:|这个约束的意图在于说明数据发布端无法保证其发送的消息条数一定要达到请求的消息条数,如果上游没这么多消息的话没法发送请求的消息条数是可以的,(当上游无法构建这么多消息条数的时候)可以以失败,空,或是完成状态来结束订阅.
|3|onSubscribe, onNext, onError and onComplete signaled to a Subscriber MUST be signalled  in a thread-safe manner -- and if performed by multiple threads -- use external synchronization|
|3|在数据订阅方/Subscriber 的 onSubscribe , onNext, onError 和 onComplete 这些调用必须以线程安全的方式来调用, 如果真的要设计到多线程调用的话, 必须借助于外部同步方法来保证其线程安全.|
|:bulb:|The intent of this rule is to make it clear that external synchronization must be employed if the Publisher intents to send singals from multiple/different threads.|
|:bulb:|这个约束/规则的意图在于确保如果数据发布方在通过多线程或是这不同线程推送数据给数据订阅方时,数据订阅方可借助外部同步方法确保线程的安全|
|4|If a Publisher failed it MUST singal an onError|
|4|数据发布方一旦执行错误必须通过调用 onError 方法来传递失败的信号|
|:bulb:|The intent of this rule is to make it clear that a Publisher is responsible for notifying its Subscribers if it detects that it can not proceed - Subscribers must be given a chance to clean up resources or otherwise deal with the Publisher's failures.|
|:bulb:|这个规范的目的是为了确保:数据发布方遇到异常的时候,是由数据发布方发现其遇到错误无法运行的时候,是尤其来负责将失败消息通知给其数据订阅方, 通过这种方式数据订阅方会在接收到失败通知消息的时候进行资源清理或者做一些其他的应对失败的处理.|
|5|If a Publisher terminates successfully(finite stream) it MUST signal an onComplete|
|5|当 Publisher 成功结束的时候(数据流是有限的情况下)它必须通过调用 onComplete 将结束的信号传递给 Subscriber|
|:bulb:|The intent of this rul is to make it clear that a Publisher is responsible for notifying its Subscribers that it has reached a terminal-state -- Subscribers can then act on this information; clean up resources, etc|
|:bulb:|这个规定的目的是为了保证: Publisher 有义务通知订阅其数据的 Subscribers 它已经达到终态 -- 这样 Subscribers 在接收到这个信息之后能够做一些资源回收清理或者是其他收尾工作|
|6|If a Publisher signals either onError or onComplete on a Subscriber, that Subscriber's Subscription MUST be considered cancelled.|
|6|如果 Publisher 通过调用 onError 或是 onComplete 任意一种方法来向 Subscriber 传递信号的话, 则认为 Subscriber 向 Publisher 所发起订阅必须被取消了. |
|:bulb:|The intent of this rule is to make sure that a Subscription is treated the same no matter if it was cancelled, the Publisher signalled onError or onComplete.|
|:bulb:|这个规定的目的是为了说明: 无论 Publisher 通过 onError 还是 onComplete 发送信号, 创建的订阅会被取消.|
|7|Once a terminal state has been signalled(onError, onComplete) it is REQUIRED that no further signals occur.|
|7| 一旦被传达了终态信号(通过 onError 或是 onComplete 方法传达的)就不允许继续再继续传递其他的信号信息了|
|:bulb:|The intent of this rule is to make sure that onError and onComplete are the final states of an interaction between a Publisher and Subscriber pair.|
|:bulb:|这个规定是为了说明, onError 和 onComplete 这两个调用方法是作为传递 Publisher 和 Subscriber 这个订阅对的终态信号量的方法,一经调用整个订阅交互的过程将会结束掉|
|8|If a Subscription is cancelled its Subscriber MUST eventually stop being singlled.|
|8|一旦一个订阅过程终止了, 那么在这个订阅对中的订阅一方必须停止向数据发送方发送请求数据的信号信息|
|:bulb:|The intent of this rule is to make sure that Publishers respect a Subscriber's request to cancel a Subscription when Subscription.cancle() has been called. The reason for eventually is because signals can have propagation delay due to being asynchronous.|
|:bulb:|这个规则的意图是确保Publisher 应该遵照 Subscriber 方停通过 Subscription.cancle() 来停止数据订阅的意图. 之所以这么规定是因为传递的信号信息有时候会因为消息的异步而存在一定概率的迟延.|
|9|Publisher.subscribe MUST call onSubscribe on the provided Subscriber prior to any other signals to that Subscriber and MUST return normally, except when the provided Subscriber is null in which case it MUST throw a java.lang.NullPointerException to the caller, for all other situations the only legal way to signal failure (or reject the Subscriber) is by calling onError (after calling onSubscribe).|
|9|Publisher 的 subscribe 方法在订阅中对其 Subscriber 而言要比其他信号调用有着更高级别的响应优先级, 以至于 Subscriber 一经接收该信号调用必须立即正确地返回, 除非 Subscriber 实例为空(尚未初始化) 在这种情况下会为调用者抛出一个 java.lang.NullPointerException 的异常, 除了这种实例为空抛异常的情况, 其他任何情况下用来描述调用失败(或是拒绝订阅过程)唯一合法的返回值便是通过调用 onError 这个方法(调用顺序应该位于 onSubscribe 方法之后)|
|:bulb:|The intent of this rule is to make sure that onSubscribe is always signalled before any of the other signals, so that initialization logic can be executed by the Subscriber when the signal is received. Also onSubscribe MUST only be called once, [see [2.12](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#2.12)]. If the supplied Subscriber is null, there is nowhere else to signal this but to the caller, which means a java.lang.NullPointerException must be thrown. Examples of possible situations: A stateful Publisher can be overwhelmed, bounded by a finite number of underlying resources, exhausted, or in a [terminal state](). |
|:bulb:|这个约束是为了保证 onSubscribe 该信号量调用优先级高于其他信号量调用方法, 以便于 Subscriber 先于其他任何信号量调用来处理 onSubscribe 调用以完成其初始化过程(防止 Subscriber 还未初始化好久就响应其他信号量调用). 同时还有一点, onSubscribe 方法必须只能被调用一次, 具体看[2.12](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#2.12) 这里. 如果响应 Publisher 的 Subscriber 实例为空(未被初始化), 就没办法响应其他的信号量调用方法, Subscriber 订阅端这边能做的只能是抛出一个 java.lang.NullPointerException 这个异常. 而无法接收到正确方式响应的 Publisher 端有可能会处于一下几种状态: Publisher 会被崩溃退出, 或是其所使用的有限资源会被耗尽, 或是到达终态而停止数据发布. |
|10|Publisher.subscribe MAY be called as many times as wanted but MUST be with a different Subscriber each time [see[2.12](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#2.12)].|
|10|Publisher 的订阅方法允许根据实际需要而被调用多次, 但必须确保的是每次的 Subscriber 均不相同,进一步了解请见 [2.12](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#2.12)|
|:bulb:|The intent of this rule is to have callers of subscribe be aware that a generic Publisher and a generc Subscriber can not be assumed to support beging attaching multiple times.Furthermore, it also mandates that the semantics of subscribe must be upheld no matter how many times it is called.|
|:bulb:|规则10的意图是为了确保订阅中的调用信号而言, 访问 Publisher 和 Subscriber 多次的情况下并不是每次都能访问得到的. 不仅如此, 该规则还说明了在订阅语义范围内, 无论订阅操作被调用几次，每次创建的订阅过程都应该被保持（而不是每次调用之后就将其释放掉）.|
|11|A Publisher MAY support multiple Subscribers and decides whether each Subscription is unicast or multicast|
|11|对于 Publisher 而言允许有多个 Subscriber 订阅其发布的数据,只需要通过设定该订阅过程是单播还是多播即可.|
|:bulb:|The intent of this rule is to give Publisher implementation the flexibility to decide how many, if any, Subscribers they will support, and how elements are going to be distributed.|
|:bulb:|规则11的意图是为了保证对于给定 Publisher 而言它在实现逻辑上支持其下游订阅方有多少个,以及以何种方式来分发数据元素给 Subscriber 的.|

### Subscriber
### 订阅者

```
public interface Subscriber<T> {
    public void onSubscribe(Subscription s) ; 
    public void onNext(T t) ; 
    public void onError(Throwable t);
    public void onComplete();
}
```

| ID | Rule | 
| ------ | ------ | 
|ID|规范|
|1|A Subscriber MUST signal demand via Subscription.request(long n) to receive onNext signals|
|1|对于数据订阅方 Subscriber 而言必须通过 Subscription.request(long n) 这个信号调用方法来开启订阅, 并通过 onNext 信号方法来进行后续的数据接收流程|
|:bulb:|The intent of this rule is to establish that it is the resposibility of the Subscriber to signal when, and how many, elements it is able and willing to receive.|
|:bulb:|这个规则的意图是,在一个订阅建立之初, 数据订阅方/Subscriber 有义务声明其何时接收数据,能接收多少数据，是否愿意开启数据的接收.|
|2|If a Subscriber suspects that its processing of signals will negatively impact its Publisher's responsivity, it is RECOMMENDED that it asynchronously dispatches its signals.|
|2|如果对于数据接收方/Subscriber 探测到其响应的信息对数据发布方/Publisher 起到负向影响, 推荐数据接收方/Subscriber 通过异步方式来调用信号方法.|
|:bulb:|The intent of this rule is that a Subscriber should not obtruct the progress of the Publisher from an execution point-of-view. In other words, the Subscriber should not starve the Publisher from receiving CPU cycles.|
|:bulb:|这个规则是为了确保从程序运行的角度来看, Subscriber 是不允许阻塞 Publisher 发布数据操作的.也就是说 Subscriber 一方不允许在 Publisher 占用 CPU 资源的周期内因为自身的原因来让 Publisher 陷入空等.|
|3|Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST NOT call any methods on the Subscription or the Publisher.|
|3|数据订阅方/Subscriber 的 onComplete 方法和其 onError(Throwable t) 方法逻辑中千万不可以调用 Subscription 或是 Publisher 中的方法.|
|:bulb:|The intent of this rule is to prevent cycles and race-conditions -- between Publisher, Subscription and Subscriber -- during the processing of completion signals.|
|:bulb:|这个约束的目的是为了防止 Publisher, Suscription 与 Subscrier 之间在调用完成信号调用时候引起的循环调用和竟态|
|4|Subscriber.onComplete() and  Subscriber.onError(Throwable t) MUST consider the Subscription cancelled after having received the signal.|
|4|Subscriber 在实现其 onComplete() 和 onError(Throwable t) 方法逻辑时必须将在接收到信号调用后整个订阅过程被取消的这种情况下所需采取的操作或是处理逻辑考虑在内.|
|:bulb:|The intent of this rule s to make sure that Subscribers respect a Publisher's terminal state signals. A Subscription is simply not valid anymore after an onComplete or onError signal has been received.|
|:bulb:|这个约束的作用是为了确保, 订阅方/Subscrier 需要遵从数据发布方/Publisher 所传递的终态信号量.一个订阅过程在接收到 onComplete 或是 onError 方法发送的信号量之后整个订阅便会失效.|
|5|A Subscriber MUST call Subscription.cancel() on the given Subscription after an onSubscribe signal if it already has an active Subscription.|
|5|如果当前的订阅过程/Subscription 已经处于活跃状态, 那么参与其中的订阅方/Subscriber 必须通过调用 Subscription 的 cancel 方法来主动退出当前的订阅过程.|
|:bulb:|The intent of this rule is to prevent that two, or more, separate Publishers from thinking that they can interact with the same Subscriber. Enforcing this rule means that resources leaks are prevented since extra Subscriptions will be cancelled.|
|:bulb:|这个规则为未来防止2到多个分隔开的数据发布者/Publisher 有着相同的的下游订阅者. 只要严格遵守这个规定的话, 就能够确保资源不会被泄露, 因为这样能够保证同一个数据订阅者/Subscripter 不会同时在多个订阅过程中(不会存在订阅多个数据源的情况)|
|6|A Subscriber MUST call Subscription.cancel() if the Subscription is no longer needed.|
|6|如果不在订阅数据的话, 数据订阅方/Subscriber 必须通过调用 Subscription 的 cancel 方法作为发起取消订阅的操作.|
|:bulb:|The intent of this rule is to establish that Subscribers cannot just throw Subscription away when they are no longer needed, they have to cancel so that resource held by that Subscription can be safely, and timely, reclaimed. An example of this would be a Subscriber which is only interested in a specific element, which whould then cancel its Subscription to signal its completion to the Publisher.|
|:bulb:|这个规则是为了确保数据订阅方/Subscriber 在与数据发布方/Publisher 建立订阅关系后，不能说不订阅数据就订阅了, 它/Subscriber 必须主动通过 onCancel 方法来发起取消操作以便于订阅过程中所占用的资源能够安全地,有规律地被释放,回收. 比方说, 数据订阅端只负责接收订阅数据流中的特定的元素, 在接收到之后就会通过调用 Subscription 的 cancel 方法来向其上游数据发布方/Publisher 发送完成数据订阅的信号.|
|7|A Subscriber MUST ensuer that all calls on its Subscrition take place from the same thread or provide for repective external synchronization.|
|7|数据的订阅方必须保证其所建立起的订阅过程中所有的调用请求都有相同的线程发起,或是如果是有多个线程发起必须要借助于外部同步方法来保证线程安全.|
|:bulb:|The intent of this rule is to establish that external synchronization must be added if a Subscriber will be using a Subscription concurrently by two or more threads.|
|:bulb:|此约束是为了保证所订阅过程必须确保是同步的,如果是订阅方有 2 到多个线程牵涉到调用中的话, 便需要借助于外部同步方法来保证线程安全.|
|8|A Subscriber MUST be prepared to receive one or more onNext signals after having called Subscription.cancel() if there is still requested elements pending [see [3.12]()]. Subscription.cancel() does not guarantee to perform the underlying cleaning operation immediately.|
|8|Subscriber 在调用 Subscription.cancel() 方法之后, 如果上游还有未传输完的数据的话, 其必须要准备好接收 1 到多个 onNext 方法调用所发来的信号量的准备.|
|:bulb:|The intent of this rule is to highlight that there may be a delay between calling cancel and the Publisher observing that cancellation.|
|:bulb:|这个规则是为了强调这样一个事情:Subscriber 在调用 cancel 方法之后会存在一定的延迟,在这段延迟期间 Publisher 是处于阻塞的.|
|9|A Subscriber MUST be prepared to receive an onComplete signal with or without a preceding Subscription.request(long n) call.|
|9|对于 Subscriber 而言, 无论他是否事先调用过 Subscription.request(long n) 这个方法,其必须做好接收并处理响应 onComplete 信号调用的方法的准备 |
|:bulb:|The intent of this rule is to establish that completion is unrelated to the demand flow -- this allows for streams which complete early, and obviates the need to poll for completion.|
 

### 3 Subscription
### 3 订阅过程

```
public interface Subscription {
    public void request(long n) ; 
    public void cancel() ; 
}
```


| ID | Rule | 
| ------ | ------ | 
|ID|规范|
|9|While the Subscription is not cancelled, Subscription.request(long n) MUST signal onError with a java.lang.IllegalArgumentException if the argument is <= 0. The cause message SHOULD explain that non-positive request signals are illegal.|
|9|当订阅过程还存在,并且所创建的 Publisher 与 Subscriber 之间连接还未取消的时候, Subscription 的 request(long n) 这个方法调用传入的参数 n<= 0 的话, 便会触发 onError 函数返回一个 java.lang.IllegalArgumentException 异常. 消息必须传达一种能够说明在调用 request 方法传入参数 <=0 是一种非法的调用这样的信息才行.|
|[:bulb:]|The intent of this rule is to prevent faulty implementation to proceed operation wihtout any exceptions beng raised. Requesting a negative or 0 number of elements, since requests are additive, most likely to the result of an erroneous calculation on the behalf of the Subscriber.|
|说明|之所以有这个规定是为了防止在执行操作出现错误之后,防止没有任何异常抛出来(导致无法定位问题). |
|10|While the Subscription is not cancelled, Subscription.request(long n) MAY synchronously call onNext on this (or other) subscriber(s).|
|10|只要是订阅过程没有结束, Subscription.request(long n) 这个方法是允许以同步的方式来自己调用 onNext 或是其他的订阅者上调用 onNext 这个方法的.|
|:bulb:|The intent of this rule is to establish that it is allowed to create synchronous Publishers,i.e. Publishers who execute their logic on the calling thread.|
|说|这个规定的意图是为了说明同步的 Publisher 的创建时被允许的,这样一来多个 Publisher 便可以在其调用线程上执行其各自的逻辑.|
|11|While the Subscription is not cancelled, Subscription.request(long n) MAY synchronously call onComplete or onError on this ( or other ) subcriber(s).|
|11|订阅过程只要是没有结束, 那么 Subscription 的 request(long n) 是允许在当前或是其他订阅者上按照同步顺序的方式来调用 onComplete 或是 onError 方法的.|
|[:bulb:]|The intent of this rule is to establish that it is allowed to create synchronous Publishers, i.e. Publishers who execute their logic on the calling thread.|
|说明|这个规则 11 是为了说明, 同步的 Publisher 是允许被创建的, 也就是说, Publisher 可以在其调用线程上执行其相关的逻辑.|
|12|While the Subscription is not cancelled, Subscription.cancel() MUST request the Publisher to eventally stop signalling its Subscriber. The operation is NOT REQUIRED to affect the Subsctiption immediately.|
|12|只要是订阅过程未被释放,Subscription.cancel() 这个方法必须通过请求 Publisher 来将终止信号发送给其 Subscriber. 但是,当这个停止信号发送给其 Subscriber 到停止信号被接收因为存在一定延迟,所以停止信号发送之后,整个订阅过程不会立即停止,会有一定的时间延迟的.|
|:bulb:|The intent of this rule is to establish that the desire to cancel a Subscription is eventually respected by the Publisher, acknowledging that it may take some time before the signal is received.|
|说明|规则12的意图是为了强调, 取消订阅过程这一请求需要遵照 Publisher 端的意愿, 也就是说在发起取消订阅请求信号到这个请求被接收与响应执行期间需要一定时间.|
|13|While the Subscription is not cancelled, Subscription.cancel() MUST request the Publisher to eventually drop any references to the corresponding subscriber.|
|13|在订阅过程声明周期内, 通过 Subscription 的 cancel 这个方法一定会触发 Publisher 将订阅其数据的 Subscriber 之间所建立的关系进行释放.|
|:bulb:|The intent of this rule is to make sure that Subscribers can be properly garbage-collected after their subscription no longer being valid. Re-subscribing with the same Subcriber object is discouraged [see [2.12](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#2.12)], but this specification does not mandate that it is disallowed since that would mean having to store previously cancelled subscriptions indefinely.|
|说明|规则 13 的意图是为了确保: 订阅者/Subscribers 在订阅过程释放后能够执行适当的垃圾回收等收尾工作.  对于相同的下游订阅者/Subscriber 而言并不鼓励订阅过程的重复使用, 因为复用的话有可能会遇到之前的订阅过程中还有一些上个订阅过程中所造成干扰的惨厉信息(即信息释放不充分,复用容易造成信息不一致).|
|14|While the Subscription is not cancelled, calling Subscription.cancel MAY cause the Publisher, if stateful, to transition into the shut-down state if not other Subscriber  exists at this point [see [1.9](https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.2#1.9)]|
|14|在订阅过程声明周期内, 调用 Subscription 的 cancel 的方法能够引起数据发布方转换到终结状态,如果数据发布方/Publlisher 存在状态且下游没有数据订阅者/Subscriber 存在.|
|:bulb:|The intent of this rule is to allow for Publishers to signal onCompute or onError following onSubscribe for new Subscribers in response to cancellation signal from an existing Subscriber.|
 

### 4. Processor
### 4. 执行过程

```
public interface Processor<T,R> extends Subscriber<T>, Publisher<R> {}
```






