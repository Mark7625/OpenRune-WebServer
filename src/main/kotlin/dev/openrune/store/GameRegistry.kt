package dev.openrune.store

import dev.openrune.cache.tools.CacheEnvironment
import dev.openrune.cache.tools.GameType
import dev.openrune.db.inTransaction
import dev.openrune.db.query
import dev.openrune.db.queryOne
import dev.openrune.db.update
import dev.openrune.db.withConnection
import dev.openrune.model.EntityTypeDef
import dev.openrune.model.Game
import javax.sql.DataSource

/**
 * Games and their entity types as stored in `game` / `entity_type`. Types are declared in code
 * ([EntityTypeDef]) and registered here so every stored row can carry a smallint type id.
 */
class GameRegistry(private val dataSource: DataSource) {

    class RegisteredType(val id: Int, val def: EntityTypeDef)

    class RegisteredGame(val game: Game, val types: List<RegisteredType>) {
        private val byKey = types.associateBy { it.def.key }
        private val byId = types.associateBy { it.id }
        fun type(key: String): RegisteredType = byKey[key] ?: error("Unknown entity type '$key' for ${game.slug}")
        fun typeOrNull(key: String): RegisteredType? = byKey[key]
        fun typeById(id: Int): RegisteredType? = byId[id]
    }

    /** Insert the game and any missing types, create its partition, and return the registered view. */
    fun register(gameType: GameType, environment: CacheEnvironment, types: List<EntityTypeDef>): RegisteredGame {
        val slug = Game.slugFor(gameType, environment)
        return dataSource.inTransaction { c ->
            val existingId = c.queryOne("SELECT id FROM game WHERE slug = ?", slug) { it.getInt(1) }
            val id = existingId ?: run {
                val next = (c.queryOne("SELECT COALESCE(MAX(id), 0) + 1 FROM game") { it.getInt(1) }) ?: 1
                c.update(
                    "INSERT INTO game (id, slug, name, environment) VALUES (?, ?, ?, ?)",
                    next, slug, Game.displayName(gameType, environment), environment.name,
                )
                next
            }
            c.queryOne("SELECT ensure_game_partition(?::smallint)", id) { }
            val existingTypes = c.query("SELECT id, key FROM entity_type WHERE game_id = ?", id) { it.getInt(1) to it.getString(2) }
                .associate { it.second to it.first }
                .toMutableMap()
            var nextTypeId = (c.queryOne("SELECT COALESCE(MAX(id), 0) + 1 FROM entity_type") { it.getInt(1) }) ?: 1
            types.forEach { def ->
                if (def.key in existingTypes) return@forEach
                c.update(
                    "INSERT INTO entity_type (id, game_id, key, kind, display_name, gameval_group) VALUES (?, ?, ?, ?, ?, ?)",
                    nextTypeId, id, def.key, def.kind.name, def.displayName, def.gamevalGroup,
                )
                existingTypes[def.key] = nextTypeId
                nextTypeId++
            }
            RegisteredGame(
                Game(id, slug, Game.displayName(gameType, environment), gameType, environment),
                types.map { def -> RegisteredType(existingTypes.getValue(def.key), def) },
            )
        }
    }

    fun games(): List<Game> = dataSource.withConnection { c ->
        c.query("SELECT id, slug, name, environment FROM game ORDER BY id") { rs ->
            val slug = rs.getString("slug")
            val env = CacheEnvironment.valueOf(rs.getString("environment"))
            val gameType = when (slug.substringBefore('-')) {
                "osrs" -> GameType.OLDSCHOOL
                "rs3" -> GameType.RUNESCAPE
                else -> GameType.valueOf(slug.substringBefore('-').uppercase())
            }
            Game(rs.getInt("id"), slug, rs.getString("name"), gameType, env)
        }
    }
}
