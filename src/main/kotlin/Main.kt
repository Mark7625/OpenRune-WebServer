import com.displee.cache.CacheLibrary
import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import mu.KotlinLogging
import routes.configureRouting
import java.io.File

private val logger = KotlinLogging.logger {}

lateinit var gameCache: dev.openrune.filesystem.Cache
    private set


lateinit var cacheLibrary: CacheLibrary
    private set

suspend fun main(args: Array<String>) {
    val rev = args.getOrNull(0)?.toIntOrNull() ?: -1
    val game = args.getOrNull(1) ?: GameType.OLDSCHOOL.toString()
    val environmentType = args.getOrNull(2) ?: CacheEnvironment.LIVE.toString()

    val gameType = GameType.valueOf(game)
    val environment = CacheEnvironment.valueOf(environmentType)

    val cacheDir =  File("./cache/${gameType}/${environment}/$rev/")

    val cacheService = CacheManagerService(rev,environment = environment,gameType = gameType, cacheDir = cacheDir)
    cacheService.prepareCache()



    val gameServices = GameServicesInitializer(cacheDir, rev, cacheService)
    gameCache = cacheService.gameCache
    cacheLibrary = CacheLibrary(cacheDir.absolutePath)
    gameServices.initialize()

    logger.info { "Starting server with rev=$rev and gameType=$gameType" }

    embeddedServer(Netty, port = 8090) {
        configureRouting(
            objectGameVals = cacheService.objectGameVals,
            itemGameVals = cacheService.itemGameVals,
            npcGameVals = cacheService.npcGameVals,
            spriteGameVals = cacheService.spriteGameVals,
            seqGameVals = cacheService.seqGameVals,
            spotGameVals = cacheService.spotGameVals,
        )
    }.start(wait = true)
}