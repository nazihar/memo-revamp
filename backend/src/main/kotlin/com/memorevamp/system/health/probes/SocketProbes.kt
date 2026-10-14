package com.memorevamp.system.health.probes

import com.memorevamp.system.health.ComponentProbe
import com.memorevamp.system.health.ComponentStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Plain TCP endpoint; disabled when [host] is blank. */
data class TcpEndpoint(
    val host: String = "",
    val port: Int = 0,
) {
    val enabled: Boolean get() = host.isNotBlank()
}

@ConfigurationProperties("memo.malware-scanner")
data class MalwareScannerProperties(
    val host: String = "",
    val port: Int = 3310,
) {
    fun endpoint() = TcpEndpoint(host, port)
}

@ConfigurationProperties("memo.mail")
data class MailProperties(
    val host: String = "",
    val port: Int = 25,
) {
    fun endpoint() = TcpEndpoint(host, port)
}

/** Opens a socket with connect and read timeouts, runs [conversation], and maps any I/O failure to DOWN. */
internal fun probeTcp(
    endpoint: TcpEndpoint,
    timeout: Duration,
    conversation: (Socket) -> Boolean,
): ComponentStatus {
    if (!endpoint.enabled) return ComponentStatus.DISABLED
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeout.toMillis().toInt())
            socket.soTimeout = timeout.toMillis().toInt()
            if (conversation(socket)) ComponentStatus.UP else ComponentStatus.DOWN
        }
    } catch (_: IOException) {
        ComponentStatus.DOWN
    }
}

/** Reads at most [max] bytes or until [terminator]; never unbounded. */
internal fun InputStream.readBounded(
    max: Int,
    terminator: Int,
): String {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
        val b = read()
        if (b < 0 || b == terminator) break
        buf[n++] = b.toByte()
    }
    return String(buf, 0, n, StandardCharsets.US_ASCII)
}

/** clamd: `zPING\0` → `PONG\0`. */
@Component
class MalwareScannerProbe(
    private val properties: MalwareScannerProperties,
) : ComponentProbe {
    override val name = "malwareScanner"

    override fun check() =
        probeTcp(properties.endpoint(), TIMEOUT) { socket ->
            socket.getOutputStream().apply {
                write("zPING\u0000".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBounded(MAX_REPLY, 0) == "PONG"
        }
}

/** SMTP: the server greets with a `220` banner; we answer QUIT and send nothing else. */
@Component
class MailProbe(
    private val properties: MailProperties,
) : ComponentProbe {
    override val name = "mail"

    override fun check() =
        probeTcp(properties.endpoint(), TIMEOUT) { socket ->
            val banner = socket.getInputStream().readBounded(MAX_REPLY, '\n'.code)
            socket.getOutputStream().apply {
                write("QUIT\r\n".toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            banner.startsWith("220")
        }
}

private val TIMEOUT: Duration = Duration.ofSeconds(2)
private const val MAX_REPLY = 512
