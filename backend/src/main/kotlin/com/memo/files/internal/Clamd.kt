package com.memo.files.internal

import com.memo.common.scan.MalwareScannerProperties
import org.springframework.stereotype.Component
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** What clamd said about a stream. Anything but [Clean] rejects the upload (fail closed, ADR-0029). */
internal sealed interface ScanVerdict {
    data object Clean : ScanVerdict

    /** A signature or heuristic matched, for example `Heuristics.Limits.Exceeded.MaxFileSize`. */
    data class Infected(
        val signature: String,
    ) : ScanVerdict

    /** The scanner could not give a verdict: unreachable, timed out, closed early, or an unexpected reply. */
    data class Failed(
        val reason: String,
    ) : ScanVerdict
}

/** Strict parser of clamd's replies to `zINSTREAM`: only the two documented success forms are accepted. */
internal object ClamdReply {
    private val FOUND = Regex("^stream: ([\\x21-\\x7E]{1,200}) FOUND$")
    private val PRINTABLE = Regex("[^\\x20-\\x7E]")

    fun parse(reply: String): ScanVerdict {
        if (reply == "stream: OK") return ScanVerdict.Clean
        val found = FOUND.matchEntire(reply)
        return if (found != null) {
            ScanVerdict.Infected(found.groupValues[1])
        } else {
            ScanVerdict.Failed("unexpected reply: " + reply.take(MAX_REASON).replace(PRINTABLE, "?"))
        }
    }

    private const val MAX_REASON = 120
}

/**
 * A minimal clamd client over TCP (ADR-0029): `zINSTREAM` streaming and `zVERSION`. No maintained Java client exists, and
 * the protocol is small: chunks prefixed with a 4-byte big-endian length, a zero-length chunk to end, one reply ending in
 * NUL. clamd closes the connection when `StreamMaxLength` is exceeded; that surfaces as [ScanVerdict.Failed].
 */
@Component
internal class ClamdClient(
    private val properties: MalwareScannerProperties,
) {
    val enabled: Boolean get() = properties.enabled

    /** Opens a scan. Throws [IOException] when clamd is unreachable (the caller maps it to "scanner unavailable"). */
    fun open(): ClamdStream {
        val socket = connect()
        return try {
            ClamdStream(socket).also { it.start() }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    /** The engine and signature version, for example `ClamAV 1.5.4/27806/Mon Nov  2 08:24:01 2026`. */
    fun version(): String =
        connect().use { socket ->
            socket.getOutputStream().apply {
                write("zVERSION\u0000".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            readReply(socket.getInputStream()).also { if (it.isBlank()) throw IOException("empty VERSION reply") }
        }

    private fun connect(): Socket {
        check(enabled) { "the malware scanner is not configured" }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(properties.host, properties.port), properties.connectTimeout.toMillis().toInt())
            socket.soTimeout = properties.readTimeout.toMillis().toInt()
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    companion object {
        const val MAX_REPLY = 1024

        /** Reads one NUL-terminated reply, at most [MAX_REPLY] bytes. */
        internal fun readReply(input: InputStream): String {
            val buf = ByteArray(MAX_REPLY)
            var n = 0
            while (n < MAX_REPLY) {
                val b = input.read()
                if (b < 0 || b == 0) break
                buf[n++] = b.toByte()
            }
            return String(buf, 0, n, StandardCharsets.US_ASCII).trimEnd('\n')
        }
    }
}

/** One `INSTREAM` scan in progress. Write the content with [write], then call [finish] exactly once. */
internal class ClamdStream(
    private val socket: Socket,
) : AutoCloseable {
    private val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
    private val header = ByteBuffer.allocate(Int.SIZE_BYTES)
    private var broken: IOException? = null

    internal fun start() {
        out.write("zINSTREAM\u0000".toByteArray(StandardCharsets.US_ASCII))
    }

    /** Sends [length] bytes of [bytes] as one chunk. After a write failure further writes are ignored; [finish] reports it. */
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (broken != null || length == 0) return
        try {
            header.clear()
            out.write(header.putInt(length).array())
            out.write(bytes, offset, length)
        } catch (e: IOException) {
            broken = e
        }
    }

    /** Ends the stream and waits for the verdict. */
    fun finish(): ScanVerdict {
        val failure = broken
        return try {
            if (failure == null) {
                out.write(ByteArray(Int.SIZE_BYTES))
                out.flush()
            }
            // Even after a failed write clamd may have explained why (e.g. "INSTREAM size limit exceeded. ERROR").
            val reply = ClamdClient.readReply(socket.getInputStream())
            when {
                reply.isNotEmpty() -> ClamdReply.parse(reply)
                failure != null -> ScanVerdict.Failed("connection lost: ${failure.message}")
                else -> ScanVerdict.Failed("empty reply")
            }
        } catch (e: IOException) {
            ScanVerdict.Failed("no verdict: ${e.javaClass.simpleName}")
        } finally {
            close()
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
