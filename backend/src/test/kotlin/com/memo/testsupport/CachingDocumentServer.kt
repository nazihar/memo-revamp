package com.memo.testsupport

import com.memo.docedit.internal.DocEditProperties
import com.memo.docedit.internal.OnlyOfficeConverter
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for ONLYOFFICE's `POST /converter/from-file` that keeps every conversion's answer by its key, failures
 * included, as the document server does (found on ONLYOFFICE 10 in E06 T12): a request under a key seen before gets that
 * key's first answer back at once, whatever the file. While [down] (the document server restarting), a new key's
 * conversion fails with -3 ("Conversion error") and that failure is what the key answers from then on. Otherwise the file
 * is converted by [convert] (the uploaded bytes, `pdf` or `pdfa`, the title). Used through [converter], the real client.
 */
class CachingDocumentServer(
    private val convert: (bytes: ByteArray, outputType: String, title: String) -> ByteArray,
) : AutoCloseable {
    private val json = JsonMapper.builder().build()
    private val answers = ConcurrentHashMap<String, Answer>()

    /** The key of every request, in order (the polls of one conversion repeat theirs). */
    val keys: MutableList<String> = CopyOnWriteArrayList()

    /** How many conversions were actually run (a cached answer is not one). */
    @Volatile var conversions: Int = 0
        private set

    @Volatile var down: Boolean = false

    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/converter/from-file") { exchange -> exchange.use { answer(it) } }
            start()
        }

    /** The application's ONLYOFFICE client pointed at this server. */
    fun converter(): OnlyOfficeConverter =
        OnlyOfficeConverter(
            DocEditProperties(documentServerUrl = "http://127.0.0.1:${server.address.port}", jwtSecret = SECRET),
            json,
            Clock.systemUTC(),
        )

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        val boundary = exchange.requestHeaders.getFirst("Content-Type").substringAfter("boundary=")
        val parts = parts(exchange.requestBody.readAllBytes(), boundary)

        @Suppress("UNCHECKED_CAST")
        val params = json.readValue(String(checkNotNull(parts["params"]), Charsets.UTF_8), Map::class.java) as Map<String, Any?>
        val key = params["key"] as String
        keys += key
        val file = parts["file"]
        val answer =
            answers[key]
                ?: when {
                    file == null -> Answer(JSON, """{"endConvert":false,"percent":0}""".toByteArray())
                    else -> answers.computeIfAbsent(key) { run(file, params) }
                }
        exchange.responseHeaders.add("Content-Type", answer.type)
        exchange.sendResponseHeaders(HTTP_OK, answer.body.size.toLong())
        exchange.responseBody.use { it.write(answer.body) }
    }

    private fun run(
        file: ByteArray,
        params: Map<String, Any?>,
    ): Answer =
        if (down) {
            Answer(JSON, """{"error":-3}""".toByteArray())
        } else {
            conversions++
            Answer("application/pdf", convert(file, params["outputtype"] as String, params["title"] as String))
        }

    private class Answer(
        val type: String,
        val body: ByteArray,
    )

    companion object {
        private const val HTTP_OK = 200
        private const val JSON = "application/json"
        private const val SECRET = "caching-document-server-secret-0123456789"

        /** The parts of a multipart body by name (the bytes as sent: ISO-8859-1 maps each byte to one char and back). */
        fun parts(
            body: ByteArray,
            boundary: String,
        ): Map<String, ByteArray> =
            String(body, Charsets.ISO_8859_1)
                .split("--$boundary")
                .mapNotNull { part ->
                    val head = part.substringBefore("\r\n\r\n", "")
                    val name = Regex("name=\"([^\"]+)\"").find(head)?.groupValues?.get(1) ?: return@mapNotNull null
                    name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
                }.toMap()
    }
}
