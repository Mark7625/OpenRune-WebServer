package cache.texture

import routes.open.cache.SpriteHandler.sprites
import dev.openrune.OsrsCacheProvider
import dev.openrune.definition.type.TextureType
import gameCache
import java.awt.image.BufferedImage


data class TextureInfo(
    val id : Int,
    val isTransparent: Boolean,
    val fileIds: MutableList<Int>,
    val averageRgb: Int,
    val animationDirection : Int,
    val animationSpeed : Int,
    @Transient val image: BufferedImage
)

object TextureManager {

    val textureCache: MutableMap<Int, TextureInfo> = mutableMapOf()
    val textures: MutableMap<Int, TextureType> = mutableMapOf()

    fun init() {
        OsrsCacheProvider.TextureDecoder().load(gameCache,textures)
        textures.forEach {
            val type = it.value
            textureCache.put(it.key, TextureInfo(
                it.key,
                type.isTransparent,
                type.fileIds,
                type.averageRgb,
                type.animationDirection,
                type.animationSpeed,
                sprites[type.fileIds.first()]!!.sprites.first().toBufferedImage()
            ))
        }
    }


}