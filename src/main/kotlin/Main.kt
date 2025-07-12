import cache.texture.TextureManager
import com.google.gson.GsonBuilder
import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.cache.tools.DownloadListener
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.util.XteaLoader
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.type.ItemType
import dev.openrune.definition.type.ObjectType
import dev.openrune.filesystem.Cache
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import mu.KotlinLogging
import routes.open.cache.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

var itemSpriteFactory: ItemSpriteFactory? = null
lateinit var modelDecoder: ModelDecoder
lateinit var cacheLoc: File
lateinit var gameCache: Cache
private val logger = KotlinLogging.logger {}

var rev = 231
var gameType: String = "OLDSCHOOL"

suspend fun main(args: Array<String>) {
    rev = args.getOrNull(0)?.toIntOrNull() ?: rev
    gameType = args.getOrNull(1) ?: gameType

    cacheLoc = File("./cache/$rev/")
    downloadCache()
    gameCache = Cache.load(cacheLoc.toPath(), true)
    objectGameVals = GameValHandler.readGameVal(GameValGroupTypes.LOCTYPES, gameCache)
    itemGameVals = GameValHandler.readGameVal(GameValGroupTypes.OBJTYPES, gameCache)
    npcGameVals = GameValHandler.readGameVal(GameValGroupTypes.NPCTYPES, gameCache)

    modelDecoder = ModelDecoder(gameCache)
    LoadModels.init()
    OpenRS2.loadCaches()
    CachesHandler.loadCaches()
    CacheManager.init(OsrsCacheProvider(gameCache, rev))



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

    routing {
        publicRoutes()
    }
}

var objectGameVals: List<GameValElement> = emptyList()
var itemGameVals: List<GameValElement> = emptyList()
var npcGameVals: List<GameValElement> = emptyList()

fun Route.publicRoutes() {
    get("/") {
        call.respondText("Hello World!", ContentType.Text.Plain)
    }

    val objectHandler = ConfigHandler(
        gameVals = objectGameVals,
        getAllConfigs = { CacheManager.getObjects() },
        getName = { it.name },
        getId = { it.id },
        getGameValName = { id -> objectGameVals.lookup(id)?.name },
        availableFilters = mapOf(
            "requireName" to { it.name.isNotBlank() && !it.name.equals("null", ignoreCase = true) },
            "interactiveOnly" to { it.interactive != 0 || it.actions.any { action -> action != null } }
        )
    )

    val itemHandler = ConfigHandler(
        gameVals = itemGameVals,
        getAllConfigs = { CacheManager.getItems() },
        getName = { it.name },
        getId = { it.id },
        getGameValName = { id -> itemGameVals.lookup(id)?.name },
        availableFilters = mapOf(
            "noted" to { !it.noted },
        ),
        extractExtraData = { item ->
            mapOf("noted" to item.noted)
        }
    )

    val npcHandler = ConfigHandler(
        gameVals = npcGameVals,
        getAllConfigs = { CacheManager.getNpcs() },
        getName = { it.name },
        getId = { it.id },
        getGameValName = { id -> npcGameVals.lookup(id)?.name },
        availableFilters = mapOf(

        )
    )

    route("/public") {

        get("/caches/{type...}") {
            CachesHandler.handle(call)
        }

        get("/npcs") {
            npcHandler.handleRequest(call)
        }

        get("/items") {
            itemHandler.handleRequest(call)
        }

        get("/objects") {
            objectHandler.handleRequest(call)
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

    }
}
