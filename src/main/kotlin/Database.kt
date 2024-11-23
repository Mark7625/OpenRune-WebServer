package org.example

import routes.user.Token
import routes.user.User
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.coroutine.CoroutineDatabase

val CLOUD_ENVIRONMENT = Environment(
    database = "ktor-secure-logi",
    host = "cluster0.6y05k3d.mongodb.net",
    username = "admin",
    password = "DpaBpeQG7vjCJMxx",
    options = "ssl=true&authSource=admin&retryWrites=true&w=majority"
)

data class Environment(val database : String, val host : String, val port : Int = 27017, val username : String?, val password : String?, val options : String?) {

    fun getConnectionLink() : String {
        return if(username == null || password == null) {
            "mongodb+srv://$host/$database"
        } else {
            "mongodb+srv://${username}:${password}@${host}/${database}?${options}"
        }
    }

    fun getAsyncLink(): String {
        return "mongodb+srv://$username:$password@${host}/?retryWrites=true&w=majority"
    }

}


object MongoDB {

    private val connectionString = ConnectionString(CLOUD_ENVIRONMENT.getConnectionLink())
    private val settings = MongoClientSettings.builder().applyConnectionString(connectionString).build()

    private val client = KMongo.createClient(settings).coroutine

    val users = database.getCollection<User>()
    val tokens = database.getCollection<Token>("tokens")

    val database: CoroutineDatabase
        get() = client.getDatabase("users")
}
