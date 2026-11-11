package com.memo.docedit.internal

import com.memo.docedit.EditMode
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/** A callback from the document server after its token was verified (research R8.2). */
data class EditorCallback(
    val key: String,
    val status: Int,
    /** Where the saved result can be fetched; statuses 2, 3, 6 and 7. */
    val url: String?,
    val forceSaveType: Int?,
) {
    /** A result to store: closed with changes, or an explicit force save (command or the Save button). */
    val saves: Boolean get() = status == STATUS_SAVE || (status == STATUS_FORCE_SAVE && forceSaveType in EXPLICIT_FORCE_SAVES)

    companion object {
        const val STATUS_EDITING = 1
        const val STATUS_SAVE = 2
        const val STATUS_SAVE_ERROR = 3
        const val STATUS_CLOSED = 4
        const val STATUS_FORCE_SAVE = 6
        const val STATUS_FORCE_SAVE_ERROR = 7
        private val EXPLICIT_FORCE_SAVES = setOf(0, 1)
    }
}

/** What the editor needs about the person and the file. */
data class EditorSubject(
    val session: EditSession,
    val fileName: String,
    val userName: String,
    val language: String,
)

/**
 * The editor adapter (ADR-0037): builds signed editor configurations and reads callbacks. ONLYOFFICE is the only
 * implementation; another editor would implement the same three operations.
 */
interface EditorAdapter {
    fun config(subject: EditorSubject): Map<String, Any?>

    /** Verifies the callback's token (body first, then the header) and reads it; throws [BadSignatureException]. */
    fun callback(
        body: Map<String, Any?>,
        authorization: String?,
    ): EditorCallback

    /** Verifies the token the document server sends with a document download. */
    fun verifyDownload(authorization: String?)
}

/**
 * ONLYOFFICE Docs (research R8): HS256 tokens with the installation's secret, the document fetched through a signed link
 * on the internal connector, callbacks to the same connector, downloads, plugins and macros off in the editor.
 */
@Component
class OnlyOfficeAdapter(
    private val properties: DocEditProperties,
    private val keys: DocumentKeys,
    private val json: JsonMapper,
    private val clock: Clock,
) : EditorAdapter {
    private val jwt by lazy { Hs256(properties.jwtSecret, json, clock) }

    override fun config(subject: EditorSubject): Map<String, Any?> {
        val session = subject.session
        val expires = clock.instant().plus(LINK_LIFETIME).epochSecond
        val base = "${properties.internalUrl.trimEnd('/')}/internal/docedit/${session.tenantId}/sessions/${session.id}"
        val content = "$base/content?exp=$expires&sig=${keys.linkSignature(session.tenantId, session.id, CONTENT, expires)}"
        val callback = "$base/callback?sig=${keys.linkSignature(session.tenantId, session.id, CALLBACK, null)}"
        val edit = session.mode == EditMode.EDIT
        val config =
            mapOf(
                "documentType" to "word",
                "document" to
                    mapOf(
                        "fileType" to "docx",
                        "key" to session.documentKey,
                        "title" to subject.fileName,
                        "url" to content,
                        "permissions" to
                            mapOf(
                                "edit" to edit,
                                "download" to false,
                                "print" to true,
                                "review" to false,
                                "comment" to false,
                                "chat" to false,
                            ),
                    ),
                "editorConfig" to
                    mapOf(
                        "mode" to if (edit) "edit" else "view",
                        "lang" to subject.language,
                        "callbackUrl" to callback,
                        "user" to mapOf("id" to session.userId.toString(), "name" to subject.userName),
                        "customization" to
                            mapOf(
                                "forcesave" to true,
                                "plugins" to false,
                                "macros" to false,
                                "help" to false,
                                "feedback" to false,
                                "goback" to false,
                                "chat" to false,
                                "compactHeader" to true,
                            ),
                    ),
            )
        return config + ("token" to jwt.sign(config + ("exp" to clock.instant().plus(TOKEN_LIFETIME).epochSecond)))
    }

    override fun callback(
        body: Map<String, Any?>,
        authorization: String?,
    ): EditorCallback {
        val payload =
            (body["token"] as? String)?.let(jwt::verify)
                ?: bearer(authorization)?.let(jwt::verify)?.let { (it["payload"] as? Map<*, *>) ?: it }
                ?: refuse("missing token")
        // The signed payload is what counts; the plain body must not say anything else.
        val key = payload["key"] as? String ?: refuse("no key")
        val status = (payload["status"] as? Number)?.toInt() ?: refuse("no status")
        if (body["key"] != null && body["key"] != key) refuse("body and token differ")
        if (body["status"] != null && (body["status"] as? Number)?.toInt() != status) refuse("body and token differ")
        return EditorCallback(key, status, payload["url"] as? String, (payload["forcesavetype"] as? Number)?.toInt())
    }

    override fun verifyDownload(authorization: String?) {
        jwt.verify(bearer(authorization) ?: refuse("missing token"))
    }

    private fun bearer(header: String?): String? = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val CONTENT = "content"
        const val CALLBACK = "callback"
        val LINK_LIFETIME: Duration = Duration.ofMinutes(5)
        val TOKEN_LIFETIME: Duration = Duration.ofHours(12)
    }
}
