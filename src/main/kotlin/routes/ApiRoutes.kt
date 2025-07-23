package routes

import cache.texture.TextureManager
import com.google.gson.GsonBuilder
import dev.openrune.cache.CacheManager
import dev.openrune.cache.gameval.GameValElement
import dev.openrune.cache.gameval.GameValHandler.lookup
import dev.openrune.cache.gameval.GameValHandler.lookupAs
import dev.openrune.cache.gameval.impl.Sprite
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import routes.open.cache.*
import routes.open.cache.web.WebTextureSyncController
import java.io.File

fun Application.configureRouting(
    objectGameVals: List<GameValElement>,
    itemGameVals: List<GameValElement>,
    npcGameVals: List<GameValElement>,
    spriteGameVals: List<GameValElement>
) {
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

    val objectHandler = ConfigHandler(
        gameVals = objectGameVals,
        getAllConfigs = { CacheManager.getObjects() },
        getName = { it.name },
        getId = { it.id },
        getGameValName = { obj -> objectGameVals.lookup(obj.id)?.name },
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
        getGameValName = { item -> itemGameVals.lookup(item.id)?.name },
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
        getGameValName = { npc -> npcGameVals.lookup(npc.id)?.name },
        availableFilters = mapOf(

        )
    )

    val textureHandler = ConfigHandler(
        gameVals = spriteGameVals,
        getAllConfigs = { TextureManager.textureCache },
        getName = { "" },
        getId = { it.id },
        getGameValName = { texture -> spriteGameVals.lookup(texture.fileIds.first())?.name },
        availableFilters = mapOf(

        ),
        extractExtraData = { texture ->
            mapOf(
                "averageRgb" to texture.averageRgb,
                "isTransparent" to texture.isTransparent,
                "animationSpeed" to texture.animationSpeed,
                "attachmentsTotal" to texture.attachments.total,
                "attachments" to texture.attachments,
                "fileIds" to texture.fileIds

            )
        }
    )

    val spriteHandler = ConfigHandler(
        gameVals = spriteGameVals,
        getAllConfigs = { SpriteHandler.sprites },
        getName = { "" },
        getId = { it.id },
        getGameValName = { sprite ->
            val lookup = spriteGameVals.lookupAs<Sprite>(sprite.id)!!
            "${lookup.name},${lookup.index}"
        },
        availableFilters = emptyMap(),
        extractExtraData = { sprite ->
            mapOf("indexedSize" to sprite.sprites.size)
        }
    )

    val modelHandler = ConfigHandler(
        gameVals = spriteGameVals,
        getAllConfigs = { LoadModels.models },
        getName = { "" },
        getId = { it.id },
        getGameValName = { "" },
        availableFilters = emptyMap(),
        extractExtraData = { models ->
            mapOf(
                "totalFaces" to models.totalFaces,
                "totalVerts" to models.totalVerts,
                "colors" to models.colors,
                "textures" to models.textures.map { TextureManager.textures[it]?.fileIds?.first() }.filterNotNull(),
                "attachmentsTotal" to models.attachments.total,
                "attachments" to models.attachments,
            )
        }
    )


    routing {
        get("/") {
            call.respondText("Hello World!", ContentType.Text.Plain)
        }

        route("/public") {
            get("/caches/{type...}") { CachesHandler.handle(call) }
            get("/npcs") { npcHandler.handleRequest(call) }
            get("/items") { itemHandler.handleRequest(call) }
            get("/objects") { objectHandler.handleRequest(call) }
            get("/textures") { textureHandler.handleRequest(call) }
            get("/sprites") { spriteHandler.handleRequest(call) }
            get("/models") { modelHandler.handleRequest(call) }

            get("/npc/{id?}") {
                call.respond(GsonBuilder().setPrettyPrinting().create().toJson(CacheManager.getNpc(2)))
            }

            get("/texture/{id?}") { TextureHandler.handleTextureById(call) }
            get("/model/{id?}") { ModelHandler.handleModelRequest(call) }
            get("/sprite/{id?}") { SpriteHandler.handleSpriteById(call) }

            get("/map") {
                call.respond(File("./data/maps/maps.json").readText())
            }

            route("web") {
                get("/textures") {
                    WebTextureSyncController.handleTextureById(call)
                }
            }

        }
    }
}
