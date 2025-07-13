import cache.texture.TextureManager
import dev.openrune.OsrsCacheProvider
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.ModelDecoder
import dev.openrune.cache.tools.OpenRS2
import dev.openrune.cache.tools.item.ItemSpriteFactory
import dev.openrune.cache.util.XteaLoader
import mu.KotlinLogging
import routes.open.cache.CachesHandler
import routes.open.cache.SpriteHandler
import java.io.File

private val logger = KotlinLogging.logger {}

class GameServicesInitializer(
    private val cacheDir: File,
    private val rev: Int,
    private val gameCache: dev.openrune.filesystem.Cache
) {
    lateinit var modelDecoder: ModelDecoder
        private set

    var itemSpriteFactory: ItemSpriteFactory? = null
        private set

    fun initialize() {
        modelDecoder = ModelDecoder(gameCache)

        LoadModels.init()
        OpenRS2.loadCaches()
        CachesHandler.loadCaches()
        CacheManager.init(OsrsCacheProvider(gameCache, rev))

        SpriteHandler.init()
        TextureManager.init()
        LoadModels.init()
        XteaLoader.load(File(cacheDir, "xteas.json"))

        itemSpriteFactory = ItemSpriteFactory(
            modelDecoder,
            TextureManager.textures,
            SpriteHandler.sprites,
            CacheManager.getItems()
        )
        logger.info { "Game services initialized." }
    }
}
