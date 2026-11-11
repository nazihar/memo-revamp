package com.memo.docedit.internal

import com.memo.common.authz.PublicEndpoint
import com.memo.common.authz.SignedIn
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.docedit.EditMode
import com.memo.system.Idempotency
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

data class OpenEditorRequest(
    val mode: EditMode = EditMode.EDIT,
    /** The UI language for the editor (BCP 47), for example `en` or `ar`. */
    val language: String = "en",
)

data class OpenEditorResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sessionId: UUID,
    /** Where the browser loads the editor script from (same host). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val scriptUrl: String,
    /** The signed editor configuration, passed to the editor as it is. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val config: Map<String, Any?>,
)

data class EditSessionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: SessionState,
    /** Why it failed or was closed, for example `LOCKED` (the memo was submitted meanwhile). */
    val reason: String?,
    val savedVersionId: UUID?,
)

data class EditingStatus(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val enabled: Boolean,
)

/** Editing for signed-in users (design E02 §4). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "docedit")
class EditSessionsController(
    private val sessions: EditSessions,
    private val properties: DocEditProperties,
    private val idempotency: Idempotency,
    private val transactions: TenantTransactions,
    private val json: JsonMapper,
) {
    @GetMapping("/files/editing")
    @SignedIn
    @Operation(operationId = "getEditingStatus", summary = "Whether documents can be edited in the browser here")
    fun status(): EditingStatus = EditingStatus(properties.enabled)

    @PostMapping("/files/{fileId}/edit-sessions")
    @SignedIn
    @Operation(operationId = "openEditor", summary = "Open a document in the browser editor")
    fun open(
        @PathVariable fileId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: OpenEditorRequest,
    ): ResponseEntity<OpenEditorResponse> {
        val language = body.language.takeIf { LANGUAGE.matches(it) } ?: "en"
        val opened =
            transactions.inTenant(TenantContext.current()) {
                val user = (ActorContext.current().actor as? Actor.User)?.userId
                // The key only guards against double clicks; every call answers with a fresh signed configuration.
                idempotency.once("docedit.open", key, "$user\n$fileId\n${json.writeValueAsString(body)}") { fileId.toString() }
                sessions.open(fileId, body.mode, language)
            }
        val script = "${properties.publicPath.trimEnd('/')}/web-apps/apps/api/documents/api.js"
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(OpenEditorResponse(opened.sessionId, script, opened.config))
    }

    @GetMapping("/edit-sessions/{id}")
    @SignedIn
    @Operation(operationId = "getEditSession", summary = "How my edit session ended")
    fun session(
        @PathVariable id: UUID,
    ): EditSessionView =
        transactions.readInTenant(TenantContext.current()) {
            sessions.own(id).let { EditSessionView(it.id, it.state, it.reason, it.savedVersionId) }
        }

    private companion object {
        val LANGUAGE = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$")
    }
}

/** The document server's endpoints (design E02 §4), on the internal connector only. */
@Hidden
@RestController
@RequestMapping(InternalConnectorConfig.PREFIX)
class DocEditInternalController(
    private val callbacks: EditorCallbacks,
) {
    @GetMapping("/{tenant}/sessions/{session}/content")
    @PublicEndpoint
    fun content(
        @PathVariable tenant: UUID,
        @PathVariable session: UUID,
        @RequestParam exp: Long,
        @RequestParam sig: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        response: HttpServletResponse,
    ) {
        callbacks.download(tenant, session, exp, sig, authorization) { version ->
            response.contentType = version.mediaType
            response.setContentLengthLong(version.sizeBytes)
            response.setHeader("Cache-Control", "no-store")
            response.outputStream
        }
    }

    /** Always `{"error":0}` for an authentic callback (the editor shows an error otherwise); `{"error":1}` when refused. */
    @PostMapping("/{tenant}/sessions/{session}/callback")
    @PublicEndpoint
    fun callback(
        @PathVariable tenant: UUID,
        @PathVariable session: UUID,
        @RequestParam sig: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Int> {
        val outcome = callbacks.callback(tenant, session, sig, body, authorization)
        return mapOf("error" to if (outcome == CallbackOutcome.REFUSED) 1 else 0)
    }
}

/** Problems of the editing endpoints: editing off, unknown file or session, a refused signature. */
@RestControllerAdvice(basePackageClasses = [EditSessionsController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DocEditProblems {
    @ExceptionHandler(EditNotFoundException::class, EditingOffException::class)
    fun notFound(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found"))

    @ExceptionHandler(BadSignatureException::class)
    fun refused(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Refused"))
}
