package com.memo.system.health.probes

import com.memo.system.health.ComponentProbe
import com.memo.system.health.ComponentStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** ONLYOFFICE Docs base URL; disabled when blank. */
@ConfigurationProperties("memo.document-server")
data class DocumentServerProperties(
    val url: String = "",
)

/** ONLYOFFICE exposes `GET /healthcheck` → `true`. */
@Component
class DocumentServerProbe(
    private val properties: DocumentServerProperties,
) : ComponentProbe {
    override val name = "documentServer"

    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun check(): ComponentStatus {
        if (properties.url.isBlank()) return ComponentStatus.DISABLED
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.url.trimEnd('/') + "/healthcheck"))
                .timeout(TIMEOUT)
                .GET()
                .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == HttpURLConnection.HTTP_OK &&
                response.body().trim() == "true"
            ) {
                ComponentStatus.UP
            } else {
                ComponentStatus.DOWN
            }
        } catch (_: IOException) {
            ComponentStatus.DOWN
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(2)
    }
}
