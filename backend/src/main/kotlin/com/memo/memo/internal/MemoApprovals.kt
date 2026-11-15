package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.identity.AuthenticationContext
import com.memo.jooq.tables.records.MemoApprovalRecord
import com.memo.jooq.tables.references.MEMO_APPROVAL
import com.memo.org.OrgDirectory
import com.memo.workflow.ActionKind
import com.memo.workflow.WorkflowQueries
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** One authenticated approval as stored (ADR-0051): the decision, its evidence, and its place in the memo's chain. */
data class MemoApproval(
    val id: UUID,
    val memoId: UUID,
    val round: Int,
    val actionSeq: Long,
    val decision: String,
    val actorId: UUID,
    val actingForId: UUID?,
    val positionId: UUID?,
    val decidedAt: Instant,
    val auth: JsonNode,
    val client: JsonNode,
    val content: JsonNode,
    val prevHash: String?,
    val recordHash: String,
)

/** `memo_approval` (V16); inside a tenant transaction, under the memo's row lock when writing. */
@Repository
class MemoApprovalStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun insert(a: MemoApproval) {
        tenantDsl
            .current()
            .insertInto(MEMO_APPROVAL)
            .set(MEMO_APPROVAL.ID, a.id)
            .set(MEMO_APPROVAL.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_APPROVAL.MEMO_ID, a.memoId)
            .set(MEMO_APPROVAL.ROUND, a.round)
            .set(MEMO_APPROVAL.ACTION_SEQ, a.actionSeq)
            .set(MEMO_APPROVAL.DECISION, a.decision)
            .set(MEMO_APPROVAL.ACTOR_ID, a.actorId)
            .set(MEMO_APPROVAL.ACTING_FOR_ID, a.actingForId)
            .set(MEMO_APPROVAL.POSITION_ID, a.positionId)
            .set(MEMO_APPROVAL.DECIDED_AT, a.decidedAt.atOffset(ZoneOffset.UTC))
            .set(MEMO_APPROVAL.AUTH, JSONB.valueOf(json.writeValueAsString(a.auth)))
            .set(MEMO_APPROVAL.CLIENT, JSONB.valueOf(json.writeValueAsString(a.client)))
            .set(MEMO_APPROVAL.CONTENT, JSONB.valueOf(json.writeValueAsString(a.content)))
            .set(MEMO_APPROVAL.PREV_HASH, a.prevHash)
            .set(MEMO_APPROVAL.RECORD_HASH, a.recordHash)
            .execute()
    }

    /** The approvals of [memoId] in the order they were made. */
    fun of(memoId: UUID): List<MemoApproval> =
        tenantDsl
            .current()
            .selectFrom(MEMO_APPROVAL)
            .where(MEMO_APPROVAL.MEMO_ID.eq(memoId))
            .orderBy(MEMO_APPROVAL.ACTION_SEQ)
            .fetch(::approval)

    private fun approval(r: MemoApprovalRecord) =
        MemoApproval(
            r.id,
            r.memoId,
            r.round,
            r.actionSeq,
            r.decision,
            r.actorId,
            r.actingForId,
            r.positionId,
            r.decidedAt.toInstant(),
            json.readTree(r.auth.data()),
            json.readTree(r.client.data()),
            json.readTree(r.content.data()),
            r.prevHash,
            r.recordHash,
        )
}

/** What a check found wrong with one approval. */
data class CheckProblem(
    val approvalId: UUID,
    /** `RECORD_CHANGED`, `CHAIN_BROKEN`, `DOCUMENT_CHANGED`, `DOCUMENT_MISSING` or `ATTACHMENT_CHANGED`. */
    val code: String,
    val detail: String,
)

/**
 * Authenticated approvals (ADR-0051, design E03 §6): each approve, recommend, return and reject writes one record with
 * the authentication of the session, the client, the hashes of the document version and the round's attachments, and
 * a record hash over all of that chained to the memo's previous record. [check] recomputes both from storage.
 */
@Service
class MemoApprovals(
    private val store: MemoApprovalStore,
    private val memos: MemoStore,
    private val attachments: MemoAttachmentStore,
    private val files: Files,
    private val queries: WorkflowQueries,
    private val directory: OrgDirectory,
    private val types: MemoTypes,
    private val authentication: AuthenticationContext,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val audit: AuditLog,
) {
    private val canonicalMapper: JsonMapper = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    /** Records the decision the actor just took on [memoId] (call right after the workflow action, in its transaction). */
    fun record(memoId: UUID) {
        val actor = (ActorContext.current().actor as? Actor.User)?.userId
        val memo = memos.find(memoId)
        val action = memo?.workflowInstanceId?.let(queries::history)?.lastOrNull { it.actorId == actor && it.kind in DECISIONS }
        if (actor == null || memo == null || action == null) return
        val evidence = authentication.current()
        val decidedAt = action.occurredAt.truncatedTo(ChronoUnit.MICROS)
        val auth =
            linkedMapOf(
                "factors" to evidence?.factors.orEmpty(),
                "authenticatedAt" to evidence?.authenticatedAt?.toString(),
                "passwordAt" to evidence?.passwordAt?.toString(),
                "secondFactorAt" to evidence?.secondFactorAt?.toString(),
                "stepUpRequired" to (action.kind == ActionKind.APPROVE && types.forMemo(memo).stepUpOnApprove),
                "sessionHash" to evidence?.sessionHash,
            )
        val client = ActorContext.current().client.let { linkedMapOf("ip" to it?.ip, "userAgent" to it?.userAgent) }
        val content =
            linkedMapOf(
                "documentVersionId" to action.snapshot["documentVersionId"],
                "documentNumber" to action.snapshot["documentNumber"],
                "sha256" to action.snapshot["sha256"],
                "attachments" to
                    attachments.bound(memo.id, action.round).map { (bound, _) ->
                        linkedMapOf("versionId" to bound.fileVersionId.toString(), "sha256" to bound.sha256)
                    },
            )
        val prev = store.of(memo.id).lastOrNull()?.recordHash
        val draft =
            MemoApproval(
                id = ids.next(),
                memoId = memo.id,
                round = action.round,
                actionSeq = action.seq,
                decision = action.kind.name,
                actorId = actor,
                actingForId = action.actingForId,
                positionId = directory.positionsOf(listOf(actor))[actor]?.id,
                decidedAt = decidedAt,
                auth = json.valueToTree(auth),
                client = json.valueToTree(client),
                content = json.valueToTree(content),
                prevHash = prev,
                recordHash = "",
            )
        store.insert(draft.copy(recordHash = hash(draft)))
    }

    fun of(memoId: UUID): List<MemoApproval> = store.of(memoId)

    /** Recomputes every record hash, the chain, and the content hashes from storage; audited. */
    fun check(memoId: UUID): List<CheckProblem> {
        val rows = store.of(memoId)
        val problems = mutableListOf<CheckProblem>()
        rows.forEachIndexed { i, row ->
            if (hash(row) != row.recordHash) problems += CheckProblem(row.id, "RECORD_CHANGED", "The record does not match its hash.")
            if (row.prevHash !=
                rows.getOrNull(i - 1)?.recordHash
            ) {
                problems +=
                    CheckProblem(row.id, "CHAIN_BROKEN", "A record before it is missing or changed.")
            }
            problems += content(row)
        }
        audit.record(
            "memo.approval.checked",
            Memos.OBJECT_TYPE,
            memoId.toString(),
            after =
                mapOf(
                    "approvals" to rows.size,
                    "problems" to problems.size,
                ),
        )
        return problems
    }

    private fun content(row: MemoApproval): List<CheckProblem> {
        val found = mutableListOf<CheckProblem>()
        val versionId =
            row.content["documentVersionId"]
                ?.takeIf { it.isString }
                ?.asString()
                ?.let(UUID::fromString)
        val document = versionId?.let(files::version)
        val number = row.content["documentNumber"]?.asInt()
        when {
            versionId == null -> {}

            document == null -> {
                found += CheckProblem(row.id, "DOCUMENT_MISSING", "Document version $number is missing.")
            }

            document.sha256 != row.content["sha256"]?.asString() -> {
                found += CheckProblem(row.id, "DOCUMENT_CHANGED", "Document version $number does not match its recorded hash.")
            }
        }
        row.content["attachments"]?.forEach { a ->
            val stored = a["versionId"]?.asString()?.let(UUID::fromString)?.let(files::version)
            if (stored == null || stored.sha256 != a["sha256"]?.asString()) {
                found += CheckProblem(row.id, "ATTACHMENT_CHANGED", "An attachment does not match its recorded hash.")
            }
        }
        return found
    }

    /** SHA-256 of the canonical JSON of [a] without its hashes, followed by the previous record's hash. */
    private fun hash(a: MemoApproval): String {
        val fields =
            mapOf(
                "id" to a.id.toString(),
                "memoId" to a.memoId.toString(),
                "round" to a.round,
                "actionSeq" to a.actionSeq,
                "decision" to a.decision,
                "actorId" to a.actorId.toString(),
                "actingForId" to a.actingForId?.toString(),
                "positionId" to a.positionId?.toString(),
                "decidedAt" to a.decidedAt.truncatedTo(ChronoUnit.MICROS).toString(),
                "auth" to canonicalMapper.treeToValue(a.auth, Map::class.java),
                "client" to canonicalMapper.treeToValue(a.client, Map::class.java),
                "content" to canonicalMapper.treeToValue(a.content, Map::class.java),
            )
        val canonical = canonicalMapper.writeValueAsString(fields) + (a.prevHash ?: "")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
    }

    private companion object {
        val DECISIONS = setOf(ActionKind.APPROVE, ActionKind.RECOMMEND, ActionKind.RETURN, ActionKind.REJECT)
    }
}
