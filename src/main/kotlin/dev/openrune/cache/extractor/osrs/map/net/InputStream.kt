package dev.openrune.cache.extractor.osrs.map.net

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

class InputStream(private val buffer: ByteBuffer) : InputStream() {

    companion object {
        private val CHARACTERS = charArrayOf(
            '\u20ac', '\u0000', '\u201a', '\u0192', '\u201e', '\u2026',
            '\u2020', '\u2021', '\u02c6', '\u2030', '\u0160', '\u2039',
            '\u0152', '\u0000', '\u017d', '\u0000', '\u0000', '\u2018',
            '\u2019', '\u201c', '\u201d', '\u2022', '\u2013', '\u2014',
            '\u02dc', '\u2122', '\u0161', '\u203a', '\u0153', '\u0000',
            '\u017e', '\u0178'
        )
    }

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    fun getArray(): ByteArray {
        require(buffer.hasArray()) { "Buffer has no array backing" }
        return buffer.array()
    }

    override fun toString(): String = "InputStream(buffer=$buffer)"

    fun read24BitInt(): Int =
        (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()

    fun skip(length: Int) {
        buffer.position(buffer.position() + length)
    }

    fun setOffset(offset: Int) {
        buffer.position(offset)
    }

    fun getOffset(): Int = buffer.position()

    fun getLength(): Int = buffer.limit()

    fun remaining(): Int = buffer.remaining()

    fun readByte(): Byte = buffer.get()

    fun readBytes(dest: ByteArray, off: Int = 0, len: Int = dest.size) {
        buffer.get(dest, off, len)
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    fun readUnsignedShort(): Int = buffer.short.toInt() and 0xFFFF

    fun readShort(): Short = buffer.short

    fun readInt(): Int = buffer.int

    fun readLong(): Long = buffer.long

    fun peek(): Byte = buffer.get(buffer.position())

    fun readBigSmart(): Int =
        if (peek() >= 0) readUnsignedShort() and 0xFFFF else readInt() and Int.MAX_VALUE

    fun readBigSmart2(): Int {
        if (peek() < 0) return readInt() and Int.MAX_VALUE
        val value = readUnsignedShort()
        return if (value == 32767) -1 else value
    }

    fun readShortSmart(): Int {
        val peek = peek().toInt() and 0xFF
        return if (peek < 128) readUnsignedByte() - 64 else readUnsignedShort() - 0xC000
    }

    fun readUnsignedShortSmartMinusOne(): Int {
        val peek = peek().toInt() and 0xFF
        return if (peek < 128) readUnsignedByte() - 1 else readUnsignedShort() - 0x8001
    }

    fun readUnsignedShortSmart(): Int {
        val peek = peek().toInt() and 0xFF
        return if (peek < 128) readUnsignedByte() else readUnsignedShort() - 0x8000
    }

    fun readUnsignedIntSmartShortCompat(): Int {
        var value = 0
        var chunk: Int
        do {
            chunk = readUnsignedShortSmart()
            value += chunk
        } while (chunk == 32767)
        return value
    }

    fun readString(): String {
        val sb = StringBuilder()
        while (true) {
            var ch = readUnsignedByte()
            if (ch == 0) break

            if (ch in 128..159) {
                var mapped = CHARACTERS[ch - 128]
                if (mapped == '\u0000') mapped = '?'
                ch = mapped.code
            }

            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    fun readString2(): String {
        if (readByte() != 0.toByte()) throw IllegalStateException("Invalid jstr2")
        return readString()
    }

    fun readStringOrNull(): String? =
        if (peek() != 0.toByte()) readString()
        else {
            readByte() // discard
            null
        }

    fun readVarInt(): Int {
        var value = 0
        var b: Int
        do {
            b = readUnsignedByte()
            value = (value shl 7) or (b and 0x7F)
        } while (b < 0)
        return value
    }

    fun readVarInt2(): Int {
        var value = 0
        var bits = 0
        var read: Int
        do {
            read = readUnsignedByte()
            value = value or ((read and 0x7F) shl bits)
            bits += 7
        } while (read > 127)
        return value
    }

    fun getRemaining(): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    @Throws(IOException::class)
    override fun read(): Int = readUnsignedByte()
}