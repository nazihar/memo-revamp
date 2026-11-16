package com.memo.files.internal

import com.memo.common.scan.MalwareScannerProperties
import com.memo.docedit.internal.Hs256
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO

/**
 * The E06 T1 spike, legacy-formats half (ADR-0055, test plan T1): what clamd (`AlertOLE2Macros`), the Tika server's
 * `PUT /detect` (Tika 4.2 has no `/detect/stream`, and serves it only when the config enables the `detect` endpoint)
 * and ONLYOFFICE make of old Office files, Outlook messages, encrypted files, RTF and multi-page TIFF.
 * The .rtf is made by ONLYOFFICE from a document POI writes; the .doc, .xls and .ppt samples (with and without macros),
 * the message and the encrypted files are Apache POI's public test files, fetched into `MEMO_SPIKE_FIXTURES` (never committed). Runs only against
 * local services: `MEMO_SPIKE_CLAMD` (host:port), `MEMO_SPIKE_TIKA`, `MEMO_SPIKE_DOCSERVER` and `MEMO_SPIKE_JWT`. The
 * results are recorded in the E06 log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MEMO_SPIKE_FIXTURES", matches = ".+")
class LegacyFormatsSpikeIT {
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val fixtures = Path.of(System.getenv("MEMO_SPIKE_FIXTURES"))
    private val docserver = System.getenv("MEMO_SPIKE_DOCSERVER").trimEnd('/')
    private val tika = System.getenv("MEMO_SPIKE_TIKA").trimEnd('/')
    private val clamd =
        System.getenv("MEMO_SPIKE_CLAMD").let { address ->
            ClamdClient(MalwareScannerProperties(host = address.substringBefore(':'), port = address.substringAfter(':').toInt()))
        }
    private val jwt = Hs256(System.getenv("MEMO_SPIKE_JWT"), json, Clock.systemUTC())

    @Test
    fun `what the scanner, the type detector and the converter make of each legacy format`() {
        val samples = linkedMapOf<String, ByteArray>()
        // ONLYOFFICE reads the old binary formats but cannot write them (error -3), so the macro-free ones are POI's
        // samples too. RTF it can write.
        samples["plain.rtf"] = convert("docx", "rtf", word())
        samples["scan.tiff"] = tiff(PAGES)
        listOf(
            "SampleDoc.doc",
            "SampleSS.xls",
            "basic_test_ppt_file.ppt",
            "SimpleMacro.doc",
            "SimpleMacro.xls",
            "SimpleMacro.ppt",
            "quick.msg",
            "password.xls",
            "password_tika_binaryrc4.doc",
            "cryptoapi-proc2356.ppt",
        ).forEach { samples[it] = Files.readAllBytes(fixtures.resolve(it)) }

        val rows =
            samples.map { (name, bytes) ->
                val verdict = scan(bytes)
                val detected = detect(bytes)
                val pdf = toPdf(name.substringAfterLast('.'), bytes)
                "$name (${bytes.size} bytes): clamd $verdict; Tika $detected; ONLYOFFICE to PDF $pdf"
            }
        rows.forEach { println("LegacyFormatsSpikeIT $it") }

        // clamd names VBA macros Heuristics.OLE2.ContainsMacros.VBA, and encrypted Word and Excel files
        // Heuristics.Encrypted.OLE2; a CryptoAPI-encrypted PowerPoint file passes it (ONLYOFFICE then answers -5).
        listOf("SimpleMacro.doc", "SimpleMacro.xls", "SimpleMacro.ppt").forEach {
            assertThat(scan(samples.getValue(it))).startsWith("Heuristics.OLE2.ContainsMacros")
        }
        assertThat(scan(samples.getValue("password.xls"))).isEqualTo("Heuristics.Encrypted.OLE2")
        assertThat(scan(samples.getValue("password_tika_binaryrc4.doc"))).isEqualTo("Heuristics.Encrypted.OLE2")
        assertThat(scan(samples.getValue("SampleDoc.doc"))).isEqualTo("clean")
        assertThat(detect(samples.getValue("SampleDoc.doc"))).isEqualTo("application/msword")
        assertThat(detect(samples.getValue("SampleSS.xls"))).isEqualTo("application/vnd.ms-excel")
        assertThat(detect(samples.getValue("basic_test_ppt_file.ppt"))).isEqualTo("application/vnd.ms-powerpoint")
    }

    private fun scan(bytes: ByteArray): String =
        clamd.open().use { stream ->
            stream.write(bytes, 0, bytes.size)
            when (val verdict = stream.finish()) {
                ScanVerdict.Clean -> "clean"
                is ScanVerdict.Infected -> verdict.signature
                is ScanVerdict.Failed -> "failed: ${verdict.reason}"
            }
        }

    private fun detect(bytes: ByteArray): String {
        val request =
            HttpRequest
                .newBuilder(URI.create("$tika/detect"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
        val answer = http.send(request, HttpResponse.BodyHandlers.ofString())
        return if (answer.statusCode() == HTTP_OK) answer.body().trim() else "HTTP ${answer.statusCode()}"
    }

    private fun toPdf(
        fileType: String,
        bytes: ByteArray,
    ): String {
        val started = System.nanoTime()
        return runCatching { convert(fileType, "pdf", bytes) }.fold(
            onSuccess = { pdf ->
                val pages = Regex("/Type\\s*/Page[^s]").findAll(String(pdf, Charsets.ISO_8859_1)).count()
                "ok, ${pdf.size} bytes, $pages pages, ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms"
            },
            onFailure = { "refused: ${it.message}" },
        )
    }

    /** The conversion API, synchronously, with the file in the request. Throws with ONLYOFFICE's error code. */
    private fun convert(
        from: String,
        to: String,
        bytes: ByteArray,
    ): ByteArray {
        val params =
            mapOf(
                "async" to false,
                "filetype" to from,
                "outputtype" to to,
                "key" to "spike-${UUID.randomUUID()}",
                "title" to "document.$from",
            )
        val token = jwt.sign(params + ("operation" to "converter"))
        val boundary = "memo-${UUID.randomUUID()}"
        val head =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val tail =
            "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"params\"\r\n\r\n${json.writeValueAsString(params)}\r\n" +
                "--$boundary\r\nContent-Disposition: form-data; name=\"token\"\r\n\r\n$token\r\n--$boundary--\r\n"
        val request =
            HttpRequest
                .newBuilder(URI.create("$docserver/converter/from-file"))
                .timeout(Duration.ofSeconds(150))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(head.toByteArray() + bytes + tail.toByteArray()))
                .build()
        val answer = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val type = answer.headers().firstValue("Content-Type").orElse("")
        check(answer.statusCode() == HTTP_OK) { "HTTP ${answer.statusCode()}" }
        if (!type.startsWith("application/json")) return answer.body()
        @Suppress("UNCHECKED_CAST")
        val result = json.readValue(answer.body(), Map::class.java) as Map<String, Any?>
        val error = result["error"]
        check(error == null) { "error $error" }
        val url = checkNotNull(result["fileUrl"] as? String) { "no result: $result" }
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray()).body()
    }

    private fun word(): ByteArray =
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().setText("Legacy memo: rent schedule for floors 3 and 4.")
            ByteArrayOutputStream().also(doc::write).toByteArray()
        }

    /** A scanned letter: [pages] pages of a grey page with a dark band, written by ImageIO's TIFF plug-in. */
    private fun tiff(pages: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("tiff").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(pages) { page ->
                val image = BufferedImage(SCAN_WIDTH, SCAN_HEIGHT, BufferedImage.TYPE_BYTE_GRAY)
                image.createGraphics().apply {
                    color = Color.LIGHT_GRAY
                    fillRect(0, 0, SCAN_WIDTH, SCAN_HEIGHT)
                    color = Color.DARK_GRAY
                    fillRect(BAND, BAND * (page + 1), SCAN_WIDTH - 2 * BAND, BAND)
                    dispose()
                }
                writer.writeToSequence(IIOImage(image, null, null), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    private companion object {
        const val HTTP_OK = 200
        const val PAGES = 3
        const val SCAN_WIDTH = 850
        const val SCAN_HEIGHT = 1100
        const val BAND = 100
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
