package com.memo.files.internal

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig2026
import com.memo.common.tenant.TenantId
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.GeneralSecurityException
import java.util.UUID
import kotlin.random.Random

/** ADR-0028: ranges decrypt to exactly the plaintext slice; tampering, truncation and moving a ciphertext are detected. */
class FileCipherProperties {
    private val tenant = TenantId(UUID.randomUUID())
    private val file = UUID.randomUUID()
    private val version = UUID.randomUUID()

    private fun aKeyset(): StreamingAead =
        KeysetHandle
            .generateNew(TenantKeysets.PARAMETERS, StreamingAeadConfig2026.get())
            .getPrimitive(StreamingAeadConfig2026.get(), StreamingAead::class.java)

    private val aead = aKeyset()

    private fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray = FileCrypto.associatedData(tenant, file, version),
        with: StreamingAead = aead,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        with.newEncryptingChannel(Channels.newChannel(out), aad).use { it.write(ByteBuffer.wrap(plaintext)) }
        return out.toByteArray()
    }

    private fun readRange(
        ciphertext: ByteArray,
        offset: Long,
        length: Int,
        aad: ByteArray = FileCrypto.associatedData(tenant, file, version),
        with: StreamingAead = aead,
    ): ByteArray =
        with.newSeekableDecryptingChannel(SeekableInMemoryByteChannel(ciphertext), aad).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            buffer.array().copyOf(buffer.position())
        }

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 40)
    fun `any range of any size decrypts to the plaintext slice`(
        @ForAll("ranges") case: Triple<Int, Int, Int>,
    ) {
        val (size, start, length) = case
        val plaintext = Random(size).nextBytes(size)
        val ciphertext = encrypt(plaintext)

        assertThat(readRange(ciphertext, start.toLong(), length)).isEqualTo(plaintext.copyOfRange(start, minOf(size, start + length)))
        assertThat(ciphertext.size).isGreaterThan(size)
    }

    @Provide
    fun ranges(): Arbitrary<Triple<Int, Int, Int>> {
        val segment = TenantKeysets.SEGMENT_BYTES
        val sizes =
            Arbitraries.oneOf(
                Arbitraries.of(0, 1, segment - 1, segment, segment + 1, 3 * segment),
                Arbitraries.integers().between(0, 4 * segment),
            )
        return sizes.flatMap { size ->
            Combinators
                .combine(Arbitraries.integers().between(0, size), Arbitraries.integers().between(0, 2 * segment))
                .`as` { start, length -> Triple(size, start, length) }
        }
    }

    @Test
    fun `a flipped byte, a truncated or reordered ciphertext fails to decrypt`() {
        val segment = TenantKeysets.SEGMENT_BYTES
        val plaintext = Random(1).nextBytes(3 * segment)
        val ciphertext = encrypt(plaintext)

        val flipped = ciphertext.copyOf().also { it[segment + 100] = (it[segment + 100] + 1).toByte() }
        assertThatThrownBy { readRange(flipped, segment.toLong(), 10) }.isInstanceOf(IOException::class.java)

        val truncated = ciphertext.copyOf(ciphertext.size - segment)
        assertThatThrownBy { readRange(truncated, 0, plaintext.size) }.isInstanceOf(IOException::class.java)

        // Header + first segment fill [0, segment); segment k (k ≥ 1) is [k × segment, (k + 1) × segment). Swap 1 and 2.
        val reordered =
            ciphertext.copyOf().also { c ->
                System.arraycopy(ciphertext, 2 * segment, c, segment, segment)
                System.arraycopy(ciphertext, segment, c, 2 * segment, segment)
            }
        assertThatThrownBy { readRange(reordered, 0, plaintext.size) }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a ciphertext moved to another version, file or tenant, or read with another keyset, does not decrypt`() {
        val ciphertext = encrypt("board minutes".toByteArray())

        listOf(
            FileCrypto.associatedData(tenant, file, UUID.randomUUID()),
            FileCrypto.associatedData(tenant, UUID.randomUUID(), version),
            FileCrypto.associatedData(TenantId(UUID.randomUUID()), file, version),
        ).forEach { otherAad ->
            assertThatThrownBy { readRange(ciphertext, 0, 13, aad = otherAad) }.isInstanceOfAny(
                IOException::class.java,
                GeneralSecurityException::class.java,
            )
        }
        assertThatThrownBy { readRange(ciphertext, 0, 13, with = aKeyset()) }.isInstanceOfAny(
            IOException::class.java,
            GeneralSecurityException::class.java,
        )
    }

    @Test
    fun `the ciphertext does not contain the plaintext`() {
        val marker = "PLAINTEXT-MARKER-".repeat(100).toByteArray()
        val ciphertext = String(encrypt(marker), Charsets.ISO_8859_1)

        assertThat(ciphertext).doesNotContain("PLAINTEXT-MARKER")
    }
}
