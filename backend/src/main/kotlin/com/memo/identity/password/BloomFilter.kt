package com.memo.identity.password

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.ln

/**
 * A compact, deterministic Bloom filter of common passwords (ADR-0023). Indices come from double hashing over SHA-256 of
 * the NFC-normalised, lower-cased password, so the file is reproducible from the same source list.
 *
 * File format: magic `MEMOBF1`, bit count (long), hash count (int), bits (big-endian longs).
 */
class BloomFilter private constructor(
    private val bitCount: Long,
    private val hashCount: Int,
    private val bits: LongArray,
) {
    fun mightContain(password: String): Boolean = indices(password).all { get(it) }

    fun add(password: String) = indices(password).forEach { set(it) }

    fun write(out: OutputStream) {
        DataOutputStream(out).apply {
            write(MAGIC)
            writeLong(bitCount)
            writeInt(hashCount)
            bits.forEach { writeLong(it) }
            flush()
        }
    }

    private fun indices(password: String): Sequence<Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalise(password).toByteArray(Charsets.UTF_8))
        val h1 = long(digest, 0)
        val h2 = long(digest, Long.SIZE_BYTES) or 1L
        return (0 until hashCount).asSequence().map { i -> Math.floorMod(h1 + i * h2, bitCount) }
    }

    private fun get(index: Long) = bits[(index ushr WORD_SHIFT).toInt()] and (1L shl (index and WORD_MASK).toInt()) != 0L

    private fun set(index: Long) {
        val word = (index ushr WORD_SHIFT).toInt()
        bits[word] = bits[word] or (1L shl (index and WORD_MASK).toInt())
    }

    companion object {
        private val MAGIC = "MEMOBF1".toByteArray(Charsets.US_ASCII)
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63L
        private const val MAX_HASHES = 64
        private const val BYTE_MASK = 0xFFL

        fun normalise(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC).lowercase()

        /** Sized for [expected] entries at false-positive rate [fpp]. */
        fun create(
            expected: Long,
            fpp: Double,
        ): BloomFilter {
            val m = ceil(-expected * ln(fpp) / (ln(2.0) * ln(2.0))).toLong()
            val k = maxOf(1, Math.round(m.toDouble() / expected * ln(2.0)).toInt())
            return BloomFilter(m, k, LongArray(((m + WORD_MASK) ushr WORD_SHIFT).toInt()))
        }

        fun read(input: InputStream): BloomFilter {
            val data = DataInputStream(input)
            val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
            require(magic.contentEquals(MAGIC)) { "not a memo Bloom filter" }
            val m = data.readLong()
            val k = data.readInt()
            require(m > 0 && k in 1..MAX_HASHES) { "corrupt Bloom filter header" }
            val bits = LongArray(((m + WORD_MASK) ushr WORD_SHIFT).toInt()) { data.readLong() }
            return BloomFilter(m, k, bits)
        }

        private fun long(
            bytes: ByteArray,
            offset: Int,
        ): Long = (0 until Long.SIZE_BYTES).fold(0L) { acc, i -> (acc shl Byte.SIZE_BITS) or (bytes[offset + i].toLong() and BYTE_MASK) }
    }
}
