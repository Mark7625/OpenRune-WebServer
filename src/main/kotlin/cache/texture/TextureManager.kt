package cache.texture

import SpriteHandler.sprites
import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.definition.data.TextureType
import dev.openrune.cache.filestore.definition.decoder.TextureDecoder
import java.awt.image.BufferedImage


data class TextureInfo(
    val id : Int,
    val isTransparent: Boolean,
    val fileIds: MutableList<Int>,
    val averageRgb: Int,
    val animationDirection : Int,
    val animationSpeed : Int,
    @Transient val image: BufferedImage // Transient prevents this field from being serialized
)

object TextureManager {

    val cache: MutableMap<Int, TextureInfo> = mutableMapOf()
    val textures: MutableMap<Int, TextureType> = mutableMapOf()

    fun init() {
        textures.putAll(TextureDecoder().load(CacheManager.cache))
        textures.forEach {
            val type = it.value
            cache.put(it.key, TextureInfo(
                it.key,
                type.isTransparent,
                type.fileIds,
                type.averageRgb,
                type.animationDirection,
                type.animationSpeed,
                sprites[type.fileIds.first()]!!.sprites!!.first().toBufferedImage()
            ))
        }
    }


}