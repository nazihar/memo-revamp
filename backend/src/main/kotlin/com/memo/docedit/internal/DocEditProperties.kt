package com.memo.docedit.internal

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * In-browser editing settings (design E02 §5). [documentServerUrl] is how the backend reaches the document server;
 * blank turns editing off. [internalUrl] is how the document server reaches the backend's internal connector.
 */
@Suppress("MagicNumber") // The defaults of design E02 §5, each overridable.
@ConfigurationProperties("memo.docedit")
data class DocEditProperties(
    val documentServerUrl: String = "",
    val publicPath: String = "/office/",
    val internalPort: Int = 8082,
    val internalUrl: String = "http://backend:8082",
    val jwtSecret: String = "",
    val sessionLifetime: Duration = Duration.ofHours(12),
    val maxSessionsPerTenant: Int = 50,
    val maxDocumentBytes: Long = 25L shl 20,
    val downloadTimeout: Duration = Duration.ofSeconds(60),
) {
    val enabled: Boolean get() = documentServerUrl.isNotBlank()

    companion object {
        /** ONLYOFFICE's shipped placeholder; the server only warns about it, we refuse to start with it. */
        const val VENDOR_PLACEHOLDER = "developer-only-not-a-real-secret"
        const val MIN_SECRET_BYTES = 32
    }
}

/** Refuses to start with editing configured but a missing, short or placeholder secret (threat model E1). */
@Component
class DocEditConfigCheck(
    private val properties: DocEditProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!properties.enabled) return
        val secret = properties.jwtSecret
        check(secret.toByteArray().size >= DocEditProperties.MIN_SECRET_BYTES && secret != DocEditProperties.VENDOR_PLACEHOLDER) {
            "memo.docedit.jwt-secret (MEMO_DOCEDIT_JWT_SECRET) must be set to the document server's JWT secret, at least " +
                "${DocEditProperties.MIN_SECRET_BYTES} bytes, when MEMO_DOCSERVER_URL is set"
        }
    }
}
