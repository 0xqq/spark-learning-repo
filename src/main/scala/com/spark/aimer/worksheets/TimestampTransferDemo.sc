import java.sql.Timestamp

val timestampStr = "2018-09-22 10:00:08"

val ts = Timestamp.valueOf(timestampStr)

println(s"timestamp=${ts}")


def datetime(): String = {
  var i = 0
  var b = ""
  while (i < 1000) {
    i = i + 1
    //天
    val day = (Math.random() * 21 + 10)
    val dd = day.formatted("%.0f")
    //println(dd)
    //小时
    val xx = (Math.random() * 14 + 10)
    val xs = xx.formatted("%.0f")
    //println(xs)
    //分钟
    val ff = (Math.random() * 49 + 10)
    val fz = ff.formatted("%.0f")
    //    println(fz)
    //秒钟
    val mm = (Math.random() * 49 + 10)
    val mz = mm.formatted("%.0f")
    // println(mz)
    //println("201808"+dd+xs+fz+mz)
    b = "2018-08-" + dd + " " + xs + ":" + fz + ":" + mz
  }
  b
}


val timefield = datetime()

println(s"timefield=${timefield}")