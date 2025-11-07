package dev.openrune.cache.extractor.osrs

import dev.openrune.ServerConfig
import dev.openrune.cache.extractor.BaseExtractor
import dev.openrune.cache.gameval.GameValHandler
import dev.openrune.cache.gameval.GameValHandler.lookupAs
import dev.openrune.cache.gameval.impl.Sprite
import dev.openrune.definition.GameValGroupTypes
import dev.openrune.definition.codec.SpriteCodec
import dev.openrune.filesystem.Cache

data class SpriteManifestEntry(
    val name: String?,
    val count: Int
)

data class SpriteImageInfo(
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val averageColor: Int,
    val subHeight: Int,
    val subWidth: Int
)


class SpritesExtractor(config: ServerConfig, cache: Cache) : BaseExtractor(config, cache) {

    private val spriteCodec = SpriteCodec()
    private val gamevals = GameValHandler.readGameVal(GameValGroupTypes.SPRITETYPES, cache)

    init {
        enableDualData("sprites")
    }

    override fun extract(index: Int, archive: Int, file: Int,  data: ByteArray) {
        val archiveData = cache.data(index, archive) ?: run {
            logger.warn("Failed to load archive data for index=$index, archive=$archive")
            return
        }

        val lookup = gamevals.lookupAs<Sprite>(archive)

        val decoded = spriteCodec.loadData(archive, archiveData)
        val combinedSprite = decoded.getSprite()

        if (decoded.sprites.size == 1) {
            savePng(combinedSprite, "sprites", "$archive.png")
        } else {
            val spriteName = lookup?.name ?: archive.toString()
            decoded.sprites.forEachIndexed { subIndex, indexedSprite ->
                val spriteImage = indexedSprite.toBufferedImage()
                savePng(spriteImage, "sprites", spriteName, "$subIndex.png")
            }
            savePng(combinedSprite, "sprites", "$archive.png")
        }


        val webDataEntry = SpriteManifestEntry(
            name = lookup?.name,
            count = decoded.sprites.size
        )
        
        updateData(archive, webDataEntry, decoded)

        stepProgress()
    }
}

