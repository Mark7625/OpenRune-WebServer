package dev.openrune.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
    val apiPoolSize: Int = 8,
    val ingestPoolSize: Int = 2,
    val apiStatementTimeoutMs: Long = 15_000,
) {
    companion object {
        /** `OPENRUNE_DATABASE_URL=jdbc:postgresql://host/db` plus optional user and password. */
        fun fromEnv(env: (String) -> String?): DatabaseConfig? {
            val url = env("OPENRUNE_DATABASE_URL") ?: return null
            return DatabaseConfig(
                jdbcUrl = url,
                user = env("OPENRUNE_DATABASE_USER"),
                password = env("OPENRUNE_DATABASE_PASSWORD"),
                apiPoolSize = env("OPENRUNE_DATABASE_API_POOL")?.toIntOrNull() ?: 8,
                ingestPoolSize = env("OPENRUNE_DATABASE_INGEST_POOL")?.toIntOrNull() ?: 2,
                apiStatementTimeoutMs = env("OPENRUNE_DATABASE_STATEMENT_TIMEOUT_MS")?.toLongOrNull() ?: 15_000,
            )
        }
    }
}

/**
 * Two connection pools over one PostgreSQL database: a request pool with a statement timeout
 * and a small ingestion pool without one, so a long import can never starve the API.
 */
class Database private constructor(
    val api: DataSource,
    val ingest: DataSource,
    private val closeables: List<AutoCloseable>,
) : AutoCloseable {

    override fun close() {
        closeables.forEach { runCatching { it.close() } }
    }

    companion object {
        fun connect(config: DatabaseConfig): Database {
            val api = pool(config, "openrune-api", config.apiPoolSize, config.apiStatementTimeoutMs)
            val ingest = pool(config, "openrune-ingest", config.ingestPoolSize, 0)
            val db = Database(api, ingest, listOf(api, ingest))
            Migrations.apply(ingest)
            return db
        }

        /** Wrap an existing data source (tests, embedded PostgreSQL). */
        fun wrap(dataSource: DataSource): Database {
            Migrations.apply(dataSource)
            return Database(dataSource, dataSource, emptyList())
        }

        private fun pool(config: DatabaseConfig, name: String, size: Int, statementTimeoutMs: Long): HikariDataSource {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                poolName = name
                maximumPoolSize = size
                minimumIdle = 1
                connectionTimeout = 10_000
                maxLifetime = 30 * 60 * 1000
                addDataSourceProperty("reWriteBatchedInserts", "true")
                addDataSourceProperty("ApplicationName", name)
                if (statementTimeoutMs > 0) {
                    connectionInitSql = "SET statement_timeout = $statementTimeoutMs"
                }
            }
            return HikariDataSource(hikari)
        }
    }
}

inline fun <T> DataSource.withConnection(block: (Connection) -> T): T = connection.use(block)

inline fun <T> DataSource.inTransaction(block: (Connection) -> T): T = connection.use { c ->
    c.autoCommit = false
    try {
        val result = block(c)
        c.commit()
        result
    } catch (e: Throwable) {
        runCatching { c.rollback() }
        throw e
    } finally {
        c.autoCommit = true
    }
}

fun Connection.prepare(sql: String, vararg params: Any?): PreparedStatement {
    val statement = prepareStatement(sql)
    params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    return statement
}

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, *params).use { statement ->
        statement.executeQuery().use { rs ->
            val out = ArrayList<T>()
            while (rs.next()) out.add(map(rs))
            out
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    prepare(sql, *params).use { statement ->
        statement.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepare(sql, *params).use { it.executeUpdate() }

fun Connection.execute(sql: String) {
    createStatement().use { it.execute(sql) }
}

/** Nullable int column (JDBC returns 0 for SQL NULL). */
fun ResultSet.getIntOrNull(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

fun ResultSet.getIntOrNull(index: Int): Int? {
    val value = getInt(index)
    return if (wasNull()) null else value
}

/**
 * Applies `db/migration/V###__name.sql` files in order. Files whose name contains `trgm` are
 * optional: a failure (missing extension privilege) is logged and recorded as skipped.
 */
object Migrations {
    /** Applied in order. A new file in `db/migration` only runs once it is listed here. */
    private val files = listOf(
        "V001__schema.sql",
        "V002__trgm.sql",
        "V003__ingest_progress.sql",
        "V004__backfill.sql",
    )

    fun apply(dataSource: DataSource) {
        dataSource.withConnection { c ->
            c.execute("CREATE TABLE IF NOT EXISTS schema_migration (version integer PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now(), skipped boolean NOT NULL DEFAULT false)")
            val applied = c.query("SELECT version FROM schema_migration") { it.getInt(1) }.toSet()
            files.forEach { file ->
                val version = file.substring(1, 4).toInt()
                if (version in applied) return@forEach
                val sql = Migrations::class.java.getResourceAsStream("/db/migration/$file")
                    ?.bufferedReader()?.readText()
                    ?: error("Missing migration resource $file")
                val optional = file.contains("trgm")
                c.autoCommit = false
                try {
                    c.execute(sql)
                    c.update("INSERT INTO schema_migration (version, name) VALUES (?, ?)", version, file)
                    c.commit()
                    logger.info { "Applied migration $file" }
                } catch (e: Exception) {
                    c.rollback()
                    if (!optional) throw e
                    logger.warn { "Optional migration $file skipped: ${e.message}" }
                    c.update("INSERT INTO schema_migration (version, name, skipped) VALUES (?, ?, true)", version, file)
                    c.commit()
                } finally {
                    c.autoCommit = true
                }
            }
        }
    }
}
