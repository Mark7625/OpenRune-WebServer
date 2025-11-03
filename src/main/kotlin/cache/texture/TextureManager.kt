package cache.texture

import LoadModels
import ModelAttachments
import cacheLibrary
import dev.openrune.Index
import routes.open.cache.SpriteHandler.sprites
import dev.openrune.OsrsCacheProvider
import dev.openrune.definition.type.OverlayType
import dev.openrune.definition.type.TextureType
import gameCache
import routes.open.cache.ModelHandler
import java.awt.image.BufferedImage


data class TextureInfo(
    val id : Int,
    val fileId : Int,
    val isTransparent: Boolean,
    val averageRgb: Int,
    val animationDirection : Int,
    val animationSpeed : Int,
    val spriteCrc : Int,
    @Transient val image: BufferedImage,
    val attachments: TextureAttachments
)

data class TextureAttachments(
    val models: ModelAttachments = ModelAttachments(mutableSetOf(), mutableSetOf(), mutableSetOf()),
    val overlays: MutableList<Int> = mutableListOf(),
    var total: Int = 0
)

object TextureManager {

    var crc = 0

    val textureCache: MutableMap<Int, TextureInfo> = mutableMapOf()
    val textures: MutableMap<Int, TextureType> = mutableMapOf()

    fun init(rev : Int) {
        OsrsCacheProvider.TextureDecoder(rev).load(gameCache,textures)

        crc = cacheLibrary.index(Index.TEXTURES).crc

        val overlays: MutableMap<Int, OverlayType> = mutableMapOf()
        OsrsCacheProvider.OverlayDecoder().load(gameCache,overlays)

        textures.forEach {
            val type = it.value

            val attachments = TextureAttachments()

            overlays.filter { overlay -> overlay.value.texture == type.id }.forEach { overlayType ->
                attachments.overlays.add(overlayType.key)
                attachments.total++
            }

            LoadModels.models
                .filter { model -> model.value.textures.contains(it.value.id) }
                .forEach { model ->
                    model.value.attachments.items.forEach { item -> attachments.models.addItem(item) }
                    model.value.attachments.objects.forEach { obj -> attachments.models.addObject(obj) }
                    model.value.attachments.npcs.forEach { npc -> attachments.models.addNpc(npc) }
                    attachments.total += model.value.attachments.total
                }

            textureCache[it.key] = TextureInfo(
                it.key,
                type.fileId,
                type.isTransparent,
                type.averageRgb,
                type.animationDirection,
                type.animationSpeed,
                cacheLibrary.index(Index.SPRITES).archive(type.fileId)!!.crc,
                sprites[type.fileId]!!.sprites.first().toBufferedImage(),
                attachments
            )


        }
    }


}