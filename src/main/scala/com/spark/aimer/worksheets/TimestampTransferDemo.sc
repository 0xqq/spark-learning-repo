import java.sql.Timestamp

val timestampStr = "2018-09-22 10:00:08"

val ts = Timestamp.valueOf(timestampStr)

println(s"timestamp=${ts}")