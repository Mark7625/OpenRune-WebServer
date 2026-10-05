package dev.openrune

import dev.openrune.db.Database
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** One embedded PostgreSQL per JVM; each test class gets its own database with the schema applied. */
object TestDatabase {
    private val server: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }
    private val counter = AtomicInteger()

    fun fresh(): Database {
        val name = "t${counter.incrementAndGet()}_${System.nanoTime()}"
        server.postgresDatabase.connection.use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val ds: DataSource = server.getDatabase("postgres", name)
        return Database.wrap(ds)
    }
}
