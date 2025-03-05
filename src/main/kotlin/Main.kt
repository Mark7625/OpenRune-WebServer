
import cache.texture.TextureManager
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.util.XteaLoader
import dev.openrune.filesystem.Cache
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import routes.open.RegistrationHandler
import routes.open.cache.CachesHandler
import routes.open.cache.ItemHandler
import routes.open.cache.ModelHandler
import routes.open.cache.TextureHandler
import routes.secure.Logout
import routes.secure.TokenManager
import java.io.File
import java.nio.file.Path
import java.lang.reflect.Type

val gameCache =  Cache.load(Path.of("./cache/"),false)
var itemSpriteFactory : ItemSpriteFactory? = null
var modelDecoder = ModelDecoder(gameCache)

suspend fun main() {
    CachesHandler.loadCaches()
    CacheManager.init(OsrsCacheProvider(gameCache,227))
    SpriteHandler.init()
    TextureManager.init()
    LoadModels.init()
    XteaLoader.load(File("./cache/xteas.json"))

    itemSpriteFactory = ItemSpriteFactory(
        modelDecoder,
        TextureManager.textures,
        SpriteHandler.sprites,
        CacheManager.getItems()
    )

    embeddedServer(Netty, port = 8090, module = Application::module).start(wait = true)
}

fun saveCrcMap(outDir: File, crcMap: Map<Int, Int>) {
    File(outDir, "maps.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(crcMap))
}


fun readCrcMap(outDir: String): Map<Int, Int> {
    val file = File(outDir, "maps.json")
    return if (file.exists()) {
        val gson = Gson()
        val mapType: Type = object : TypeToken<Map<Int, Int>>() {}.type
        gson.fromJson(file.reader(), mapType)
    } else emptyMap()
}


//suspend fun processRegions(regions : List<Region>, dumper: MapImageDumper, scale : Int) = coroutineScope {
//    val totalRegions = regions.size * Region.Z
//    var completedRegions = 0
//    val progressLock = Any()  // For thread-safe progress updates
//    MapImageDumper.MAP_SCALE = scale
//    // Process regions concurrently
//    regions.forEach { region ->
//        (0 until Region.Z).forEach { i ->
//            launch(Dispatchers.IO) {
//                // This operation is now run in a coroutine
//                val image = withContext(Dispatchers.Default) {
//                    dumper.drawRegion(regions.find { it.regionID == region.regionID }!!, i)
//                }
//
//                val outDir = File(File("./data/maps/${scale}/"), "$i")
//                outDir.mkdirs()
//                val imageFile = File(outDir, "${region.regionID}.png")
//                ImageIO.write(image, "png", imageFile)
//
//                // Thread-safe increment of completed regions
//                synchronized(progressLock) {
//                    completedRegions++
//                    printProgressBar(completedRegions, totalRegions)
//                }
//            }
//        }
//    }
//}

fun printProgressBar(done: Int, total: Int) {
    val progress = (done.toDouble() / total * 100).toInt()
    val progressBar = "=".repeat(progress / 2) + " ".repeat(50 - progress / 2)
    print("\rProgress: [$progressBar] $progress% ($done/$total)")
}


fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    install(CORS) {
        anyHost() // For development, replace with specific host in production
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "ktor.io"
            verifier(JWT.require(Algorithm.HMAC256("your-secret")).build())
            validate { credential ->
                if (credential.payload.getClaim("email").asString() != null &&
                    !TokenManager.isTokenExpired(credential.payload.getClaim("exp").asLong())) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    routing {
        publicRoutes()
        authenticate("auth-jwt") {
            privateRoutes()
        }
    }
}

fun Route.publicRoutes() {
    get("/") {
        call.respondText("Hello World!", ContentType.Text.Plain)
    }
    route("/public") {
        post("/register") {
            RegistrationHandler.handleRegistration(call)
        }
        get("/caches/{type...}") {
            CachesHandler.handle(call)
        }
        get("/item/{id?}") {
            ItemHandler.handleItemRequest(call)
        }

        get("/npc/{id?}") {
            call.respond(Gson().newBuilder().setPrettyPrinting().create().toJson(CacheManager.getNpc(2)))
        }

        get("/item/{id}/icon") {
            ItemHandler.handleItemRequest(call)
        }
        get("/texture/{id?}") {
            TextureHandler.handleTextureById(call)
        }
        get("/model/{id?}") {
            ModelHandler.handleModelRequest(call)
        }

        get("/sprite/{id?}") {
            SpriteHandler.handleSpriteById(call)
        }
        get("/map/") {
            call.respond(File("./data/maps/maps.json").readText())
        }
        get("/map/{scale?}/{plane?}/{id?}") {
            MapHandler.handle(call)
        }


    }
}

fun Route.privateRoutes() {
    route("/private") {
        post("/logout") {
            Logout.handleLogout(call)
        }
        get("/verify-token") {
            TokenManager.verifyToken(call)
        }
    }
}

