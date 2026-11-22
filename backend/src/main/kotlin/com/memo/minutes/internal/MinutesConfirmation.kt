package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.StepUpCheck
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.files.Files
import com.memo.identity.AuthenticationContext
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingMinutes
import com.memo.meeting.MeetingViewers
import com.memo.minutes.MinutesApproved
import com.memo.notification.NotificationKind
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** One authenticated approval as the minutes page shows it (ADR-0051's stamp); [client] for auditors only. */
data class MinutesApprovalResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val by: MinutesPerson,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val role: MinutesRole,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
    /** The factors of the sign-in: `PASSWORD`, `TOTP`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val factors: List<String>,
    /** How long before the approval the step-up's factor was proven, in seconds. */
    val stepUpAgeSeconds: Long?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val docxVersionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val docxSha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recordHash: String,
    /** IP address and user agent: auditors only. */
    val client: Map<String, String?>?,
)

/** What a check of the approvals found (ADR-0051): nothing when everything matches. */
data class MinutesApprovalCheckResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val approvalId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val matches: Boolean,
    /** `RECORD_CHANGED`, `CHAIN_BROKEN`, `DOCUMENT_CHANGED` or `DOCUMENT_MISSING`, each with words. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: List<MinutesCheckProblem>,
)

data class MinutesCheckProblem(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val detail: String,
)

/**
 * The chair's confirmation (ADR-0066, ADR-0051, design E06 §1.6.4): only the confirmer (the chair in office of the
 * approving meeting, or today; the vice-chair when the chair is recused from any minuted item or there is none), never on
 * their behalf, with a fresh step-up, while the minutes wait for them. In one transaction, in the lock order of design E06
 * §1.4: every decision of the meeting becomes final (its rows locked), the meeting moves HELD → MINUTES_APPROVED (its row;
 * attendance and declarations freeze), then the minutes' row: the `minutes_approval` row with ADR-0051's fields and the
 * record hash chained to the committee's previous approval (written under the chain's lock), the minutes APPROVED, the
 * release on approval scheduled. Audited `minutes.confirmed`; the readers are told; [MinutesApproved] is published.
 */
@Service
class MinutesConfirmation(
    private val store: MinutesStore,
    private val approvals: MinutesApprovalStore,
    private val decisions: DecisionStore,
    private val people: MinutesPeople,
    private val viewers: MeetingViewers,
    private val meetingMinutes: MeetingMinutes,
    private val release: MinutesReleaseScheduler,
    private val records: MinutesRecords,
    private val files: Files,
    private val authentication: AuthenticationContext,
    private val stepUp: StepUpCheck,
    private val notifier: MinutesNotifier,
    private val users: UserSummaries,
    private val policy: Policy,
    private val events: ApplicationEventPublisher,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val canonical: JsonMapper = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    /**
     * The actor confirms [meetingId]'s minutes, or not (the order of the refusals, before any step-up is asked for): 404 for
     * a meeting they do not see, 403 `NOT_CONFIRMER` for anyone but the confirmer. Read in a transaction of its own.
     */
    fun requireConfirmer(meetingId: UUID): MinutesRow {
        val view = viewers.of(meetingId)
        val row = store.find(meetingId) ?: throw MinutesNotFoundException("minutes")
        val confirmer = people.confirmer(row)
        if (confirmer?.userId != view.userId) {
            throw ForbiddenException(null, "NOT_CONFIRMER", "Only ${confirmer?.role?.words() ?: "the chair"} approves these minutes.")
        }
        return row
    }

    /** Confirms [meetingId]'s minutes at [version] (412 otherwise): the checks before ran outside the transaction. */
    fun confirm(
        meetingId: UUID,
        version: Long,
    ): MinutesRow {
        val view = viewers.of(meetingId)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // The lock order (design E06 §1.4): the decisions, the meeting, then the minutes.
        decisions.finalizeMeeting(meetingId, now)
        val found = store.find(meetingId) ?: throw MinutesNotFoundException("minutes")
        MinutesStateMachine.next(found.state, MinutesTransition.CONFIRM, found.policy.approval)
        val meeting = meetingMinutes.approved(meetingId)
        val row = checkNotNull(store.lock(meetingId))
        if (row.version != version) throw ConcurrentModificationException("minutes $meetingId", version)
        val next = MinutesStateMachine.next(row.state, MinutesTransition.CONFIRM, row.policy.approval)
        val confirmer = confirmerOf(row, view.userId)
        val docx = checkedDocx(row)
        approvals.lockChain(row.forumId)
        val approval = approval(row, confirmer, docx.id, docx.sha256, approvals.head(row.forumId), now)
        approvals.insert(approval)
        val after = row.copy(state = next, approvedAt = now, confirmedApprovalId = approval.id)
        check(store.update(row, after)) { "the locked minutes moved on" }
        store.insertEvent(
            MinutesEventRow(ids.next(), meetingId, MinutesEventKind.CONFIRMED, null, view.userId, docx.id, docx.sha256, now),
        )
        audit.record(
            "minutes.confirmed",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after =
                mapOf(
                    "state" to next.name,
                    "approvalId" to approval.id.toString(),
                    "role" to confirmer.role.name,
                    "recordHash" to approval.recordHash,
                    "previousHash" to approval.previousHash,
                    "docxVersionId" to docx.id.toString(),
                    "docxSha256" to docx.sha256,
                    "meetingStatus" to meeting.status.name,
                ),
        )
        // The record (E06 T7): version 1, of this approval, made by the `minutes-record` job.
        records.request(after, approval)
        release.approved(meetingId)
        events.publishEvent(MinutesApproved(TenantContext.current().value, meetingId, row.forumId))
        notifier.notify(people.readers(after).keys, NotificationKind.MINUTES_APPROVED, meetingId)
        return after
    }

    /** [row]'s confirmer, who must be [userId] (403 `NOT_CONFIRMER`). */
    private fun confirmerOf(
        row: MinutesRow,
        userId: UUID,
    ): MinutesOfficer {
        val confirmer = people.confirmer(row)
        if (confirmer?.userId != userId) throw ForbiddenException(null, "NOT_CONFIRMER", "Only the chair approves these minutes.")
        return confirmer
    }

    /** The draft's current version, checked sound (the check ran before the transaction and is still the current one). */
    private fun checkedDocx(row: MinutesRow): com.memo.files.FileVersionInfo {
        val docx =
            row.fileId
                ?.let(files::info)
                ?.currentVersionId
                ?.let(files::version)
                ?: throw RefusedException("NO_DRAFT", "Make the draft first.")
        refuseIf(row.checkedVersionId != docx.id || row.structureOk != true, "STRUCTURE") {
            "A newer version of the minutes was saved: check it and try again."
        }
        return docx
    }

    /** The step-up the confirmation needs (the tenant's window); throws the step-up problem when it is stale. */
    fun requireStepUp() = stepUp.require()

    private fun approval(
        row: MinutesRow,
        confirmer: MinutesOfficer,
        docxVersionId: UUID,
        docxSha256: String,
        previous: String?,
        at: Instant,
    ): ApprovalRow {
        val evidence = authentication.current()
        val proven = stepUp.provenAt()
        val auth =
            linkedMapOf(
                "factors" to evidence?.factors.orEmpty(),
                "authenticatedAt" to evidence?.authenticatedAt?.toString(),
                "passwordAt" to evidence?.passwordAt?.toString(),
                "secondFactorAt" to evidence?.secondFactorAt?.toString(),
                "stepUpRequired" to true,
                "stepUpAt" to proven?.toString(),
                "stepUpAgeSeconds" to proven?.let { Duration.between(it, at).seconds.coerceAtLeast(0) },
                "sessionHash" to evidence?.sessionHash,
            )
        val client = ActorContext.current().client.let { linkedMapOf("ip" to it?.ip, "userAgent" to it?.userAgent) }
        val draft =
            ApprovalRow(
                id = ids.next(),
                meetingId = row.meetingId,
                forumId = row.forumId,
                kind = KIND_CONFIRMATION,
                actorId = confirmer.userId,
                role = confirmer.role,
                authentication = json.valueToTree(auth),
                client = json.valueToTree(client),
                docxVersionId = docxVersionId,
                docxSha256 = docxSha256,
                previousHash = previous,
                recordHash = "",
                at = at,
            )
        return draft.copy(recordHash = hash(draft))
    }

    /** [meetingId]'s approvals as stamps, for the readers of the approved minutes (404 otherwise). */
    fun approvalsOf(meetingId: UUID): List<MinutesApprovalResponse> {
        requireApprovedReader(meetingId)
        val rows = approvals.ofMeeting(meetingId)
        val names = users.names(rows.map { it.actorId }.toSet())
        val auditor = policy.allows(Permission.AUDIT_READ)
        return rows.map { a ->
            MinutesApprovalResponse(
                id = a.id,
                kind = a.kind,
                by = MinutesPerson(a.actorId, names[a.actorId].orEmpty()),
                role = a.role,
                at = a.at,
                factors = a.authentication["factors"]?.let { f -> (0 until f.size()).map { f.get(it).asString() } }.orEmpty(),
                stepUpAgeSeconds = a.authentication["stepUpAgeSeconds"]?.takeIf { it.isNumber }?.asLong(),
                docxVersionId = a.docxVersionId,
                docxSha256 = a.docxSha256,
                recordHash = a.recordHash,
                client =
                    if (auditor) {
                        mapOf(
                            "ip" to a.client["ip"]?.takeIf { it.isString }?.asString(),
                            "userAgent" to a.client["userAgent"]?.takeIf { it.isString }?.asString(),
                        )
                    } else {
                        null
                    },
            )
        }
    }

    /** Recomputes [approvalId]'s record hash, its place in the committee's chain and the .docx's hash from storage; audited. */
    fun check(
        meetingId: UUID,
        approvalId: UUID,
    ): MinutesApprovalCheckResponse {
        requireApprovedReader(meetingId)
        val row = approvals.ofMeeting(meetingId).firstOrNull { it.id == approvalId } ?: throw MinutesNotFoundException("approval")
        val chain = approvals.ofForum(row.forumId)
        val index = chain.indexOfFirst { it.id == row.id }
        val problems =
            buildList {
                if (hash(row) != row.recordHash) add(MinutesCheckProblem("RECORD_CHANGED", "The approval does not match its hash."))
                if (row.previousHash != chain.getOrNull(index - 1)?.recordHash) {
                    add(MinutesCheckProblem("CHAIN_BROKEN", "An approval before it is missing or changed."))
                }
                val docx = files.version(row.docxVersionId)
                when {
                    docx == null -> {
                        add(MinutesCheckProblem("DOCUMENT_MISSING", "The approved document is missing."))
                    }

                    docx.sha256 != row.docxSha256 -> {
                        add(
                            MinutesCheckProblem("DOCUMENT_CHANGED", "The approved document does not match its hash."),
                        )
                    }
                }
            }
        audit.record(
            "minutes.approval.checked",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after = mapOf("approvalId" to approvalId.toString(), "problems" to problems.map { it.code }),
        )
        return MinutesApprovalCheckResponse(approvalId, problems.isEmpty(), problems)
    }

    /** The approved minutes' readers (design E06 §1.9): officers, members in office, `forums.manage` and `forum.read`; 404 otherwise. */
    private fun requireApprovedReader(meetingId: UUID) {
        val view = viewers.of(meetingId)
        val row = store.find(meetingId)?.takeIf { it.state == MinutesStage.APPROVED } ?: throw MinutesNotFoundException("minutes")
        val reads = view.manages || view.officer || view.reader || people.readers(row).containsKey(view.userId)
        if (!reads) throw MinutesNotFoundException("minutes")
    }

    /** SHA-256 of the canonical JSON of [a] without its own hash, followed by the previous approval's hash. */
    fun hash(a: ApprovalRow): String {
        val fields =
            mapOf(
                "id" to a.id.toString(),
                "meetingId" to a.meetingId.toString(),
                "forumId" to a.forumId.toString(),
                "kind" to a.kind,
                "actorId" to a.actorId.toString(),
                "role" to a.role.name,
                "authentication" to canonical.treeToValue(a.authentication, Map::class.java),
                "client" to canonical.treeToValue(a.client, Map::class.java),
                "docxVersionId" to a.docxVersionId.toString(),
                "docxSha256" to a.docxSha256,
                "at" to a.at.truncatedTo(ChronoUnit.MICROS).toString(),
            )
        val text = canonical.writeValueAsString(fields) + (a.previousHash ?: "")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }

    private fun MinutesRole.words() = if (this == MinutesRole.CHAIR) "the chair" else "the vice-chair"

    companion object {
        const val KIND_CONFIRMATION = "CONFIRMATION"
    }
}
