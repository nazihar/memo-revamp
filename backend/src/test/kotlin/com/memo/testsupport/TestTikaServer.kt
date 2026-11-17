package com.memo.testsupport

import com.sun.net.httpserver.HttpServer
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the Tika server's `PUT /detect` (ADR-0055), one per test JVM, so the upload pipeline's legacy-format
 * path runs without the 4.2 image. It types an OLE2 compound file by its entries as Tika's container detector does
 * (a Word document stream, an Excel workbook, a PowerPoint document, Outlook properties) and answers 200 with the media
 * type. While [down], it drops every connection unanswered, as an unreachable server would. Text extraction stays with
 * [FakeRenditionServices] (primary in every context), so only `/detect` is served.
 */
object TestTikaServer {
    /** Detection requests answered or dropped since the JVM started. */
    val requests = AtomicInteger()

    @Volatile var down: Boolean = false

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/detect") { exchange ->
                requests.incrementAndGet()
                if (down || exchange.requestMethod != "PUT") {
                    exchange.close()
                    return@createContext
                }
                val type = typeOf(exchange.requestBody.readAllBytes()).toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.sendResponseHeaders(HTTP_OK, type.size.toLong())
                exchange.responseBody.use { it.write(type) }
            }
            start()
        }
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** Spring properties pointing the application at this server. */
    fun properties(): Map<String, String> = mapOf("memo.files.tika-url" to url)

    private fun typeOf(bytes: ByteArray): String {
        val names =
            runCatching { POIFSFileSystem(bytes.inputStream()).use { fs -> fs.root.entryNames.toSet() } }
                .getOrElse { return "application/octet-stream" }
        return when {
            "WordDocument" in names -> "application/msword"
            "Workbook" in names || "Book" in names -> "application/vnd.ms-excel"
            "PowerPoint Document" in names -> "application/vnd.ms-powerpoint"
            names.any { it.startsWith("__substg1.0_") } -> "application/vnd.ms-outlook"
            "EncryptionInfo" in names -> "application/x-tika-ooxml-protected"
            else -> "application/x-tika-msoffice"
        }
    }

    private const val HTTP_OK = 200
}
