package dev.openrune.model

import java.security.MessageDigest

object Hashing {
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    /** First 16 bytes of SHA-256; used as the content address for payloads and blobs. */
    fun hash16(bytes: ByteArray): ByteArray = digest.get().digest(bytes).copyOf(16)

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        bytes.forEachIndexed { i, b ->
            chars[i * 2] = alphabet[(b.toInt() ushr 4) and 0xF]
            chars[i * 2 + 1] = alphabet[b.toInt() and 0xF]
        }
        return String(chars)
    }
}
