package dev.openrune.tools

import dev.openrune.db.DatabaseConfig
import dev.openrune.envOrProp
import dev.openrune.loadDotEnv
import java.sql.DriverManager

/**
 * Drops every platform table so the schema is recreated from scratch on the next start.
 * Destructive: requires `confirm=true`.
 */
fun main(args: Array<String>) {
    val flags = ToolArgs(args)
    require(flags.bool("confirm")) { "Pass confirm=true to drop all platform tables" }
    loadDotEnv()
    val config = DatabaseConfig.fromEnv(::envOrProp) ?: error("OPENRUNE_DATABASE_URL must be set")
    DriverManager.getConnection(config.jdbcUrl, config.user, config.password).use { c ->
        c.createStatement().use { st ->
            listOf("entity_ref", "entity_version", "entity_payload", "entity_blob", "revision_artifact", "ingest_run", "revision", "entity_type", "game", "schema_migration")
                .forEach { st.execute("DROP TABLE IF EXISTS $it CASCADE") }
            st.execute("DROP FUNCTION IF EXISTS ensure_game_partition(smallint)")
        }
    }
    println("Platform tables dropped from ${config.jdbcUrl}")
}
