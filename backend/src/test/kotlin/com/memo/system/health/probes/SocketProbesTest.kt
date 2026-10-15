package com.memo.system.health.probes

import com.memo.system.health.ComponentStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** In-process fake servers: no network beyond loopback, no sleeps. */
class SocketProbesTest {
    private val servers = mutableListOf<ServerSocket>()

    @AfterEach
    fun close() = servers.forEach { it.close() }

    /** Accepts one connection and runs [handler] on it. */
    private fun fakeServer(handler: (java.net.Socket) -> Unit): Int {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        servers += server
        thread(isDaemon = true) {
            runCatching { server.accept().use(handler) }
        }
        return server.localPort
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun clamd(port: Int) = MalwareScannerProbe(MalwareScannerProperties("127.0.0.1", port))

    private fun smtp(port: Int) = MailProbe(MailProperties("127.0.0.1", port))

    @Test
    fun `clamd answering PONG is UP`() {
        val port =
            fakeServer { s ->
                val cmd = ByteArray(6).also { s.getInputStream().readNBytes(it, 0, 6) }
                if (String(cmd, StandardCharsets.US_ASCII) == "zPING\u0000") s.getOutputStream().write("PONG\u0000".toByteArray())
            }
        assertThat(clamd(port).check()).isEqualTo(ComponentStatus.UP)
    }

    @Test
    fun `clamd answering something else is DOWN`() {
        val port = fakeServer { s -> s.getOutputStream().write("UNKNOWN COMMAND\u0000".toByteArray()) }
        assertThat(clamd(port).check()).isEqualTo(ComponentStatus.DOWN)
    }

    @Test
    fun `connection refused is DOWN`() {
        assertThat(clamd(freePort()).check()).isEqualTo(ComponentStatus.DOWN)
        assertThat(smtp(freePort()).check()).isEqualTo(ComponentStatus.DOWN)
    }

    @Test
    fun `silent server is DOWN within the read timeout`() {
        val port = fakeServer { s -> s.getInputStream().read() }
        val began = System.nanoTime()
        assertThat(smtp(port).check()).isEqualTo(ComponentStatus.DOWN)
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(4))
    }

    @Test
    fun `smtp 220 banner is UP and the probe says QUIT`() {
        val received = CompletableFuture<String>()
        val port =
            fakeServer { s ->
                s.getOutputStream().write("220 mail.example ESMTP\r\n".toByteArray())
                received.complete(s.getInputStream().readNBytes(6).toString(StandardCharsets.US_ASCII))
            }
        assertThat(smtp(port).check()).isEqualTo(ComponentStatus.UP)
        assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo("QUIT\r\n")
    }

    @Test
    fun `smtp error banner is DOWN`() {
        val port = fakeServer { s -> s.getOutputStream().write("554 no service\r\n".toByteArray()) }
        assertThat(smtp(port).check()).isEqualTo(ComponentStatus.DOWN)
    }

    @Test
    fun `unconfigured endpoints are DISABLED`() {
        assertThat(MalwareScannerProbe(MalwareScannerProperties()).check()).isEqualTo(ComponentStatus.DISABLED)
        assertThat(MailProbe(MailProperties()).check()).isEqualTo(ComponentStatus.DISABLED)
        assertThat(DocumentServerProbe(DocumentServerProperties()).check()).isEqualTo(ComponentStatus.DISABLED)
        assertThat(ObjectStorageProbe(StorageProperties()).check()).isEqualTo(ComponentStatus.DISABLED)
        // endpoint without credentials (e.g. the migration job) must not crash bean creation
        assertThat(ObjectStorageProbe(StorageProperties(endpoint = "http://storage:8333")).check()).isEqualTo(ComponentStatus.DISABLED)
    }
}
