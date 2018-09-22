import com.alibaba.fastjson.{JSON, JSONObject}

val jsonStr = "{\"id\":1,\"organization_id\":1,\"username\":\"admin\",\"role_ids\":\"1,2,3,4\",\"locked\":false}"

val jsonObj = JSON.parseObject(jsonStr, classOf[JSONObject])
