package com.memo.docedit.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileContents
import com.memo.files.FileUploads
import com.memo.files.FileVersionInfo
import com.memo.files.Files
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** What a callback did, for the answer to the document server and for tests. */
enum class CallbackOutcome { REFUSED, ACCEPTED, SAVED, NOT_SAVED }

/**
 * The document server's side of an edit session (design E02 §3), on the internal connector: it fetches the document
 * through a signed link and reports through signed callbacks. A save is stored through the files pipeline as the
 * session's user, so scanning, inspection, record storage and the purpose's lock apply exactly as for an upload.
 */
@Service
class EditorCallbacks(
    private val properties: DocEditProperties,
    private val store: EditSessionStore,
    private val adapter: EditorAdapter,
    private val keys: DocumentKeys,
    private val files: Files,
    private val contents: FileContents,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
    private val notifier: EditNotifier,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.downloadTimeout)
            .build()

    /** Streams the session's base version to the document server. Throws [BadSignatureException] or [EditNotFoundException]. */
    fun download(
        tenant: UUID,
        sessionId: UUID,
        expires: Long,
        signature: String,
        authorization: String?,
        out: (FileVersionInfo) -> OutputStream,
    ) {
        keys.verifyLink(tenant, sessionId, OnlyOfficeAdapter.CONTENT, expires, signature)
        adapter.verifyDownload(authorization)
        val (session, version) =
            inTenant(tenant) {
                val session = store.find(sessionId)?.takeIf { it.state == SessionState.OPEN } ?: throw EditNotFoundException()
                val version = files.version(session.baseVersionId) ?: throw EditNotFoundException()
                audit.record(
                    "file.opened",
                    "file.version",
                    version.id.toString(),
                    after = mapOf("fileId" to session.fileId.toString(), "channel" to "editor", "sessionId" to session.id.toString()),
                )
                session to version
            }
        check(session.baseVersionId == version.id)
        TenantContext.call(TenantId(tenant)) { contents.write(version, out(version)) }
    }

    fun callback(
        tenant: UUID,
        sessionId: UUID,
        signature: String,
        body: Map<String, Any?>,
        authorization: String?,
    ): CallbackOutcome =
        try {
            keys.verifyLink(tenant, sessionId, OnlyOfficeAdapter.CALLBACK, null, signature)
            val callback = adapter.callback(body, authorization)
            val session =
                inTenant(tenant) { store.find(sessionId) }?.takeIf { it.documentKey == callback.key }
                    ?: refuse("unknown session or key")
            when {
                callback.saves -> save(session, callback)
                callback.status == EditorCallback.STATUS_CLOSED -> finish(session, SessionState.CLOSED, null)
                callback.status == EditorCallback.STATUS_SAVE_ERROR -> finish(session, SessionState.FAILED, "EDITOR_SAVE_ERROR")
                else -> inTenant(tenant) { store.touch(session.id, clock.instant()) }.let { CallbackOutcome.ACCEPTED }
            }
        } catch (e: BadSignatureException) {
            refused(tenant, sessionId, e.message)
        }

    /**
     * Fetches the result from the document server only (the callback's host is ignored, see [internalDownload]), then
     * stores it as the session's user. The file must still be on the session's base version (an upload meanwhile would be
     * overwritten otherwise). A download path elsewhere is refused ([BadSignatureException]).
     */
    private fun save(
        session: EditSession,
        callback: EditorCallback,
    ): CallbackOutcome =
        try {
            val url = internalDownload(callback.url) ?: refuse("download path")
            val bytes = fetch(url) ?: notSaved("DOWNLOAD_FAILED")
            val file = inTenant(session.tenantId) { files.info(session.fileId) } ?: notSaved("GONE")
            if (file.currentVersionId != session.baseVersionId) notSaved("SUPERSEDED")
            val base = inTenant(session.tenantId) { files.version(session.baseVersionId) }
            val key = "docedit:${session.id}:${callback.status}:${digest(callback.url)}"
            val saved =
                runCatching {
                    ActorContext.call(ActorScope(Actor.User(session.userId), null)) {
                        TenantContext.call(TenantId(session.tenantId)) {
                            uploads.uploadVersion(session.fileId, file.version, base?.name ?: "document.docx", bytes, key)
                        }
                    }
                }.getOrElse {
                    log.info("Edit session {} could not store its result: {}", session.id, it.javaClass.simpleName)
                    notSaved(LOCKED)
                }
            recordSave(session, callback, saved)
            CallbackOutcome.SAVED
        } catch (e: NotSavedException) {
            finish(session, SessionState.FAILED, e.reason)
        }

    private fun recordSave(
        session: EditSession,
        callback: EditorCallback,
        saved: UUID,
    ) {
        val now = clock.instant()
        inTenant(session.tenantId) {
            val changed =
                if (callback.status == EditorCallback.STATUS_SAVE) {
                    store.finish(session.id, SessionState.SAVED, null, saved, now)
                } else {
                    store.rebase(session.id, saved, now)
                }
            // A repeated callback stored nothing new (the upload key): it is not a second save.
            if (changed) {
                audit.record(
                    "docedit.version.saved",
                    EditSessions.OBJECT_TYPE,
                    session.id.toString(),
                    after = mapOf("versionId" to saved.toString(), "status" to callback.status),
                )
            }
        }
    }

    /** The saved document, or null when the document server does not answer 200 or it exceeds the size limit. */
    private fun fetch(url: URI): ByteArray? {
        val response =
            runCatching {
                http.send(
                    HttpRequest
                        .newBuilder(url)
                        .timeout(properties.downloadTimeout)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            }.getOrNull()
        return response?.body()?.use { input ->
            if (response.statusCode() == HTTP_OK) {
                input.readNBytes((properties.maxDocumentBytes + 1).toInt()).takeIf { it.size <= properties.maxDocumentBytes }
            } else {
                null
            }
        }
    }

    private fun finish(
        session: EditSession,
        state: SessionState,
        reason: String?,
    ): CallbackOutcome {
        inTenant(session.tenantId) {
            if (store.finish(session.id, state, reason, null, clock.instant())) {
                audit.record(
                    "docedit.session.closed",
                    EditSessions.OBJECT_TYPE,
                    session.id.toString(),
                    after = mapOf("outcome" to state.name, "reason" to reason),
                )
                if (reason == LOCKED) notifier.notSaved(session)
            }
        }
        return if (state == SessionState.FAILED) CallbackOutcome.NOT_SAVED else CallbackOutcome.ACCEPTED
    }

    private fun refused(
        tenant: UUID,
        sessionId: UUID,
        reason: String?,
    ): CallbackOutcome {
        log.warn("Editor callback for session {} refused: {}", sessionId, reason)
        runCatching {
            inTenant(tenant) {
                audit.record("docedit.callback.refused", EditSessions.OBJECT_TYPE, sessionId.toString(), after = mapOf("reason" to reason))
            }
        }
        return CallbackOutcome.REFUSED
    }

    /**
     * Where to fetch a saved result. ONLYOFFICE names it on the address browsers use (`https://<tenant>/office/cache/…`,
     * from `X-Forwarded-Host`), so the host is ignored: only a path under the document server's cache is accepted, and it
     * is always fetched from the configured internal address (no SSRF through a forged URL, research R8.4).
     */
    private fun internalDownload(url: String?): URI? {
        val parsed = url?.let { runCatching { URI.create(it) }.getOrNull() }
        val path =
            parsed
                ?.rawPath
                ?.removePrefix(properties.publicPath.trimEnd('/'))
                ?.takeIf { it.startsWith(CACHE_PATH) && !it.contains("..") && !it.contains("%2e", ignoreCase = true) }
        val query = parsed?.rawQuery?.let { "?$it" }.orEmpty()
        return path?.let { URI.create(properties.documentServerUrl.trimEnd('/') + it + query) }
    }

    private fun <T> inTenant(
        tenant: UUID,
        block: () -> T,
    ): T = ActorContext.asSystem("docedit") { transactions.inTenant(TenantId(tenant), block) }

    private companion object {
        const val HTTP_OK = 200
        const val CACHE_PATH = "/cache/"
        const val LOCKED = "LOCKED"
        const val DIGEST_CHARS = 32

        fun digest(text: String?): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.orEmpty().toByteArray())).take(DIGEST_CHARS)
    }
}

/** A save that cannot land; the session ends `FAILED` with [reason]. */
private class NotSavedException(
    val reason: String,
) : RuntimeException(reason)

private fun notSaved(reason: String): Nothing = throw NotSavedException(reason)
