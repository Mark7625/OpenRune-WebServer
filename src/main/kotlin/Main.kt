import io.ktor.server.engine.*
import io.ktor.server.netty.*
import mu.KotlinLogging
import routes.configureRouting
import java.io.File

private val logger = KotlinLogging.logger {}

lateinit var gameCache: dev.openrune.filesystem.Cache
    private set

suspend fun main(args: Array<String>) {
    val rev = args.getOrNull(0)?.toIntOrNull() ?: 231
    val gameType = args.getOrNull(1) ?: "OLDSCHOOL"

    val cacheDir = File("./cache/$rev/")
    val cacheService = CacheManagerService(rev, cacheDir)
    cacheService.prepareCache()

    val gameServices = GameServicesInitializer(cacheDir, rev, cacheService.gameCache)
    gameCache = cacheService.gameCache
    gameServices.initialize()

    logger.info { "Starting server with rev=$rev and gameType=$gameType" }

    embeddedServer(Netty, port = 8090) {
        configureRouting(
            objectGameVals = cacheService.objectGameVals,
            itemGameVals = cacheService.itemGameVals,
            npcGameVals = cacheService.npcGameVals,
            spriteGameVals = cacheService.spriteGameVals,
        )
    }.start(wait = true)
}