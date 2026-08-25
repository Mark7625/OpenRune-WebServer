package dev.openrune

import java.io.File

/**
 * Resolve config from process env, then JVM system properties (populated from `.env`).
 */
fun envOrProp(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Load a dotenv-style file into system properties when the key is not already in the process env.
 * Call once at process start (before reading CDN / other config).
 */
fun loadDotEnv(file: File = File(".env")) {
    if (!file.isFile) return
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        if (key.isEmpty()) return@forEach
        if (System.getenv(key) != null) return@forEach
        var value = line.substring(eq + 1).trim()
        if (
            (value.startsWith('"') && value.endsWith('"') && value.length >= 2) ||
            (value.startsWith('\'') && value.endsWith('\'') && value.length >= 2)
        ) {
            value = value.substring(1, value.length - 1)
        }
        System.setProperty(key, value)
    }
}
