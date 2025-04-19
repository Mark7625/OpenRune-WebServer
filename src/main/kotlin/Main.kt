import cache.GameVals
import cache.texture.TextureManager
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.util.XteaLoader
import dev.openrune.definition.Js5GameValGroup
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
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import mu.KotlinLogging
import routes.open.RegistrationHandler
import routes.open.cache.*
import routes.secure.Logout
import routes.secure.TokenManager
import java.io.*
import java.lang.reflect.Type
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

var itemSpriteFactory: ItemSpriteFactory? = null
lateinit var modelDecoder: ModelDecoder
lateinit var cacheLoc: File
lateinit var gameCache: Cache
private val logger = KotlinLogging.logger {}

var rev = 230
var gameType: String = "OLDSCHOOL"

suspend fun main(args: Array<String>) {
    rev = args.getOrNull(0)?.toIntOrNull() ?: rev
    gameType = args.getOrNull(1) ?: gameType

    cacheLoc = File("./cache/$rev/")
    downloadCache()
    gameCache = Cache.load(cacheLoc.toPath(), true)
    modelDecoder = ModelDecoder(gameCache)
    LoadModels.init()
    OpenRS2.loadCaches()
    CachesHandler.loadCaches()
    CacheManager.init(OsrsCacheProvider(gameCache, rev))
    GameVals.loadGameVals()

    SpriteHandler.init()
    TextureManager.init()
    LoadModels.init()
    XteaLoader.load(File(cacheLoc,"xteas.json"))

    itemSpriteFactory = ItemSpriteFactory(
        modelDecoder,
        TextureManager.textures,
        SpriteHandler.sprites,
        CacheManager.getItems()
    )

    logger.info { "Starting server with rev=$rev and gameType=$gameType" }
    embeddedServer(Netty, port = 8090, module = Application::module).start(wait = true)
}

fun downloadCache() {
    if (!cacheLoc.exists()) {
        OpenRS2.downloadCacheByRevision(rev,cacheLoc, listener =  object : DownloadListener {
            var progressBar: ProgressBar? = null

            override fun onProgress(progress: Int, max: Long, current: Long) {
                if (progressBar == null) {
                    progressBar = ProgressBarBuilder()
                        .setTaskName("Downloading Cache")
                        .setInitialMax(max)
                        .build()
                } else {
                    progressBar?.stepTo(current)
                }
            }

            override fun onError(exception: Exception) {
                error("Error downloading: $exception")
            }

            override fun onFinished() {
                progressBar?.close()
                val zipLoc = File(cacheLoc, "disk.zip")
                if (unzip(zipLoc, cacheLoc)) {
                    logger.info { "Cache downloaded and unzipped successfully." }
                    zipLoc.delete()
                } else {
                    error("Error unzipping cache.")
                }
                OpenRS2.downloadKeysByRevision(rev, cacheLoc)
            }
        })
    }
}

fun unzip(zipFile: File, destDir: File): Boolean {
    return try {
        if (!destDir.exists()) destDir.mkdirs()

        ZipInputStream(FileInputStream(zipFile)).use { zipInputStream ->
            var zipEntry: ZipEntry?
            while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                val outputFile = File(destDir, zipEntry!!.name.substringAfterLast('/'))
                if (!zipEntry!!.isDirectory) {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { zipInputStream.copyTo(it) }
                }
                zipInputStream.closeEntry()
            }
        }

        logger.info { "Unzipped successfully" }
        true
    } catch (e: IOException) {
        logger.error { "Error while unzipping: ${e.message}" }
        false
    }
}

fun saveCrcMap(outDir: File, crcMap: Map<Int, Int>) {
    val gson = GsonBuilder().setPrettyPrinting().create()
    File(outDir, "maps.json").writeText(gson.toJson(crcMap))
}

fun readCrcMap(outDir: String): Map<Int, Int> {
    val file = File(outDir, "maps.json")
    return if (file.exists()) {
        val gson = Gson()
        val type: Type = object : TypeToken<Map<Int, Int>>() {}.type
        gson.fromJson(file.reader(), type)
    } else emptyMap()
}

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
        anyHost()
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
                val email = credential.payload.getClaim("email").asString()
                val exp = credential.payload.getClaim("exp").asLong()
                if (email != null && !TokenManager.isTokenExpired(exp)) {
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

        get("/item/{id}/icon") {
            ItemHandler.handleItemRequest(call)
        }

        get("/npc/{id?}") {
            call.respond(GsonBuilder().setPrettyPrinting().create().toJson(CacheManager.getNpc(2)))
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

        get("/map") {
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
