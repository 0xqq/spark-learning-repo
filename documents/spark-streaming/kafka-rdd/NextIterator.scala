package org.apache.spark.util 

// Provides a basic/boilerplate Iterator implementation 
// NextIterator 继承了 Iterator ,  并在其中自定义了需要的方法
private[spark] abstract class NextIterator[U] extends Iterator[U] {
    private var gotNext = false 
    private var nextValue:U = _
    private var closed = false 
    protected var finished = false 

    /**
     Method for subclasses to implement to provide the next element. 

     If no next element is available, the subclass should set `finished` 
     to `true` and may return any value (it will be ignored).

     This convention is required because `null` may be valid value,
     and using `Option` seems like it might create unnecessary Some/None 
     instance, give some iterators might be called in a tight loop. 

     @return U, or set `finished` when done
    */ 
    /**
     定义的这个 getNext 方法是由子类来提供具体的实现逻辑来返回下一个元素的.
 
     如果下一个数据元素无法访问的话, 子类中的处理逻辑应该讲成员变量 `finished` 置为 `true`
     获取也可以返回任意类型的元素.

     上述的这种处理是十分有必要的, 因为如果擅自返回 null 的话会被 [U] 认为是有效的数值,
     而使用 Option 的话, 有可能会创建某些不必要的 Some/None 的对象实例, 这些数值有可能会
     被传递给接下来的遍历中即将被访问的 迭代器. 

     @return 返回值为 U 类型变量, 若是遍历结束将 `finished` 置为 `true` 即可
    */
    protected def getNext():U 

    /**
      Method for subclasses to implement when all elements have been successfully iterated, and the iteration is done. 

      <b>Note</b> `NextIterator` cannot guarantee that `close` will be called because it has no control over that happens 
      when an exception happens in the user code that is calling hasNext/next. 

      Ideally you should have another try/catch, as in HadoopRDD, that ensures any resources are closed should iteration fail. 
    */
    /**
     这个函数的具体实现逻辑由子类给出, 当所有元素均被遍历, 整个迭代过程结束完成后被调用. 
     
     <b>值得留意的是</b> `NextIterator` 这个类无法确保 `close` 方法一定会被调用, 因为在用户代码中 hasNext/next 函数调用期间所发生的异常是无法
     被 `NextIterator` 所感知的, 所以无法调用 `close` 方法来进行收尾. 
 
     理想的情况下, 在自己代码上下文环境内, 执行 hasNext/next 方法的时候需要使用 try/catch 来修饰, 就像是 HadoopRDD 这个 RDD 的子类中所做的一样, 
     通过 try/catch 方式来确保迭代失败的时候一切打开的资源或是创建的连接能够被正确地释放. 
    */
     protected def close()

    /**
     Calls the subclass-defined close method, but only once 

     Usually calling `close` multiple times should be fine, but historically there have been issue with some InputFormats throwing exceptions. 
    */
    /**
     下面的这个方法调用之后会触发调起 `NextIterator` 子类中的 close 方法, 但是仅仅会调起一次。
      虽然 close 函数多次调用也 ok, 但是从历史 bug 来看如果多次调用的话, 特别是处理方法是与 InputFormats 交互的话, 
      多次调用 close 方法会抛异常(所以我们加了 1 次的限制).
    */
    def closeIfNeeded() = {
    	if (!closed) {
    		// Note: it is important that we set closed = true before calling close(), since setting it 
    		// afterwards would permits us to call close() multiple times if close() threw an exception. 
    		// 在这里需要注意, 在我们调用 close() 函数之前, 先要将 closed 这个成员 flag 变量置为 true, 
    		// 因为将其置位 true 会使得后续的 close() 函数调用中防止我们重复多次调用 close 函数多次, 特别是当 close 函数会抛出异常的这种情况
    		close = true 
    		close() 
    	}
    }

    // ----- 上面定义的方法, 都是 NextIterator 自己加上的, 而下面的方法是继承自  Iterator 类进行重写的方法

    // hasNext 通过返回 true/false 来标识迭代器是否遍历完所有元素了
    override def hasNext:Boolean = {
    	if (!finished) {
            nextValue = getNext() 
            if (finished) {
            	closeIfNeeded()
            }
            getNext = true 
    	}
    	!finished
    }


    // next 方法负责返回迭代器所指向的下一个元素, 如果迭代结束则返回异常提示信息
    override def next():U = {
    	if (!hasNext) {
    		throw new NoSucheElementException("End of stream")
    	}
        gotNext = false  
        nextValue 
    }
}