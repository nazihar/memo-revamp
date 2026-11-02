package com.memo.files.internal

import com.memo.common.scan.MalwareScannerProperties
import com.memo.testsupport.TestMalwareScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

/** ADR-0029 against a real clamd (test signatures) and against misbehaving fake servers: never "clean" by accident. */
class ClamdClientIT {
    private val client =
        ClamdClient(MalwareScannerProperties(host = TestMalwareScanner.host, port = TestMalwareScanner.port))

    private fun scan(
        bytes: ByteArray,
        client: ClamdClient = this.client,
    ): ScanVerdict =
        client.open().use { stream ->
            bytes.asList().chunked(CHUNK).forEach { stream.write(it.toByteArray(), 0, it.size) }
            stream.finish()
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `clean content is clean, EICAR and a planted marker are found, also inside a zip`() {
        assertThat(scan(Random(1).nextBytes(300_000))).isEqualTo(ScanVerdict.Clean)
        assertThat(scan(TestMalwareScanner.eicar)).isInstanceOf(ScanVerdict.Infected::class.java)
        assertThat(scan(zip("report.txt" to "quarterly ${TestMalwareScanner.MARKER} numbers".toByteArray())))
            .isEqualTo(ScanVerdict.Infected("Memo.Test.Marker.UNOFFICIAL"))
    }

    @Test
    fun `the version names the engine`() {
        assertThat(client.version()).startsWith("ClamAV 1.5")
    }

    @Test
    fun `an unreachable scanner fails to open`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = ClamdClient(MalwareScannerProperties(host = "127.0.0.1", port = closedPort))

        assertThatThrownBy { unreachable.open() }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a scanner that closes early, answers garbage or never answers gives no verdict`() {
        fakeClamd { _ -> }.use { server ->
            assertThat(scan(Random(2).nextBytes(500_000), server.client())).isInstanceOf(ScanVerdict.Failed::class.java)
        }
        fakeClamd { socket -> socket.getOutputStream().write("PONG\u0000".toByteArray()) }.use { server ->
            assertThat(scan("hello".toByteArray(), server.client())).isInstanceOf(ScanVerdict.Failed::class.java)
        }
        fakeClamd { socket ->
            socket.getInputStream().readAllBytes()
        }.use { server ->
            assertThat(scan("hello".toByteArray(), server.client(Duration.ofMillis(500)))).isInstanceOf(ScanVerdict.Failed::class.java)
        }
    }

    /** A one-connection-at-a-time fake clamd running [behaviour] on each accepted socket, then closing it. */
    private class FakeClamd(
        behaviour: (java.net.Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val worker =
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching { server.accept().use { behaviour(it) } }
                }
            }

        fun client(readTimeout: Duration = Duration.ofSeconds(5)) =
            ClamdClient(MalwareScannerProperties(host = "127.0.0.1", port = server.localPort, readTimeout = readTimeout))

        override fun close() {
            server.close()
            worker.join(1_000)
        }
    }

    private fun fakeClamd(behaviour: (java.net.Socket) -> Unit) = FakeClamd(behaviour)

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
