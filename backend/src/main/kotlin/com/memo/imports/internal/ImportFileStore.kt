package com.memo.imports.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ImportsFileRecord
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_STAGED
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** An original's intake state (design E06 §4.3). */
enum class IntakeState { EXPECTED, RECEIVED, REFUSED, PURGED }

/** A registered path and, once uploaded, its staged version. */
data class IntakeEntry(
    val id: UUID,
    val batchId: UUID,
    val path: String,
    /** As registered; the received size once stored. */
    val sizeBytes: Long,
    val state: IntakeState,
    val fileId: UUID?,
    val versionId: UUID?,
    /** Lower-case hex. */
    val sha256: String?,
    val mediaType: String?,
    val refusal: String?,
    val refusalSignature: String?,
    val receivedAt: Instant?,
)

/** What the upload pipeline stored for an original. */
data class ReceivedVersion(
    val fileId: UUID,
    val versionId: UUID,
    val sha256: String,
    val mediaType: String,
    val sizeBytes: Long,
)

/** Counts of a batch's originals by state, with their bytes. */
data class IntakeCount(
    val files: Int,
    val bytes: Long,
)

/** `imports_file`, inside a tenant transaction. */
@Repository
class ImportFileStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Registers [paths] (path to size) as EXPECTED; paths the batch already has keep their state and size. */
    fun register(
        batchId: UUID,
        paths: Map<String, Long>,
    ) {
        if (paths.isEmpty()) return
        val now = now()
        val tenant = tenantDsl.tenant().value
        val insert =
            tenantDsl
                .current()
                .insertInto(
                    IMPORTS_FILE,
                    IMPORTS_FILE.ID,
                    IMPORTS_FILE.TENANT_ID,
                    IMPORTS_FILE.BATCH_ID,
                    IMPORTS_FILE.PATH,
                    IMPORTS_FILE.PATH_FOLD,
                    IMPORTS_FILE.SIZE_BYTES,
                    IMPORTS_FILE.STATE,
                    IMPORTS_FILE.REGISTERED_AT,
                )
        paths.entries
            .fold(insert) { step, (path, size) ->
                step.values(ids.next(), tenant, batchId, path, PathRules.fold(path), size, IntakeState.EXPECTED.name, now)
            }.onConflictDoNothing()
            .execute()
    }

    /** The entries of [paths] that the batch has, by path. */
    fun byPaths(
        batchId: UUID,
        paths: Collection<String>,
    ): Map<String, IntakeEntry> =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_FILE)
            .where(IMPORTS_FILE.BATCH_ID.eq(batchId), IMPORTS_FILE.PATH.`in`(paths))
            .fetch(::entry)
            .associateBy { it.path }

    fun find(id: UUID): IntakeEntry? =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_FILE)
            .where(IMPORTS_FILE.ID.eq(id))
            .fetchOne(::entry)

    fun lock(id: UUID): IntakeEntry? =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_FILE)
            .where(IMPORTS_FILE.ID.eq(id))
            .forUpdate()
            .fetchOne(::entry)

    /** Entries in [states] (all when empty), ordered by state and path, after the keyset position [after]. */
    fun page(
        batchId: UUID,
        states: Set<IntakeState>,
        after: Pair<IntakeState, String>?,
        limit: Int,
    ): List<IntakeEntry> =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_FILE)
            .where(IMPORTS_FILE.BATCH_ID.eq(batchId))
            .and(if (states.isEmpty()) DSL.noCondition() else IMPORTS_FILE.STATE.`in`(states.map { it.name }))
            .and(after?.let { (state, path) -> DSL.row(IMPORTS_FILE.STATE, IMPORTS_FILE.PATH).gt(state.name, path) } ?: DSL.noCondition())
            .orderBy(IMPORTS_FILE.STATE, IMPORTS_FILE.PATH)
            .limit(limit)
            .fetch(::entry)

    /** Counts and bytes by state. */
    fun counts(batchId: UUID): Map<IntakeState, IntakeCount> =
        tenantDsl
            .current()
            .select(IMPORTS_FILE.STATE, DSL.count(), DSL.sum(IMPORTS_FILE.SIZE_BYTES))
            .from(IMPORTS_FILE)
            .where(IMPORTS_FILE.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_FILE.STATE)
            .fetch { r -> IntakeState.valueOf(r.value1().orEmpty()) to IntakeCount(r.value2() ?: 0, r.value3()?.toLong() ?: 0) }
            .toMap()

    fun receive(
        id: UUID,
        version: ReceivedVersion,
    ) = tenantDsl
        .current()
        .update(IMPORTS_FILE)
        .set(IMPORTS_FILE.STATE, IntakeState.RECEIVED.name)
        .set(IMPORTS_FILE.FILE_ID, version.fileId)
        .set(IMPORTS_FILE.VERSION_ID, version.versionId)
        .set(IMPORTS_FILE.SHA256, HexFormat.of().parseHex(version.sha256))
        .set(IMPORTS_FILE.MEDIA_TYPE, version.mediaType)
        .set(IMPORTS_FILE.SIZE_BYTES, version.sizeBytes)
        .setNull(IMPORTS_FILE.REFUSAL)
        .setNull(IMPORTS_FILE.REFUSAL_SIGNATURE)
        .set(IMPORTS_FILE.RECEIVED_AT, now())
        .where(IMPORTS_FILE.ID.eq(id))
        .execute()

    fun refuse(
        id: UUID,
        reason: String,
        signature: String?,
    ) = tenantDsl
        .current()
        .update(IMPORTS_FILE)
        .set(IMPORTS_FILE.STATE, IntakeState.REFUSED.name)
        .setNull(IMPORTS_FILE.FILE_ID)
        .setNull(IMPORTS_FILE.VERSION_ID)
        .setNull(IMPORTS_FILE.SHA256)
        .setNull(IMPORTS_FILE.MEDIA_TYPE)
        .set(IMPORTS_FILE.REFUSAL, reason)
        .set(IMPORTS_FILE.REFUSAL_SIGNATURE, signature?.take(MAX_SIGNATURE))
        .setNull(IMPORTS_FILE.RECEIVED_AT)
        .where(IMPORTS_FILE.ID.eq(id))
        .execute()

    private fun entry(r: ImportsFileRecord): IntakeEntry =
        IntakeEntry(
            id = r.id,
            batchId = r.batchId,
            path = r.path,
            sizeBytes = r.sizeBytes,
            state = IntakeState.valueOf(r.state ?: IntakeState.EXPECTED.name),
            fileId = r.fileId,
            versionId = r.versionId,
            sha256 = r.sha256?.let { HexFormat.of().formatHex(it) },
            mediaType = r.mediaType,
            refusal = r.refusal,
            refusalSignature = r.refusalSignature,
            receivedAt = r.receivedAt?.toInstant(),
        )

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val MAX_SIGNATURE = 200
    }
}

/** What a staged version is: a source or an original. */
enum class StagedKind { SOURCE, ORIGINAL }

/**
 * `imports_staged`: every staged version a batch was given, so that a replaced one is purged as well as the current ones
 * (design E06 §4.3). Inside a tenant transaction.
 */
@Repository
class StagedVersionStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun add(
        batchId: UUID,
        versionId: UUID,
        kind: StagedKind,
    ) {
        tenantDsl
            .current()
            .insertInto(IMPORTS_STAGED)
            .set(IMPORTS_STAGED.TENANT_ID, tenantDsl.tenant().value)
            .set(IMPORTS_STAGED.VERSION_ID, versionId)
            .set(IMPORTS_STAGED.BATCH_ID, batchId)
            .set(IMPORTS_STAGED.KIND, kind.name)
            .set(IMPORTS_STAGED.STAGED_AT, now())
            .onConflictDoNothing()
            .execute()
    }

    /** [versionId] is no longer used by its batch (a later upload took its place). */
    fun supersede(versionId: UUID) {
        tenantDsl
            .current()
            .update(IMPORTS_STAGED)
            .set(IMPORTS_STAGED.SUPERSEDED_AT, now())
            .where(IMPORTS_STAGED.VERSION_ID.eq(versionId), IMPORTS_STAGED.SUPERSEDED_AT.isNull)
            .execute()
    }

    /** Superseded versions of [batchId] not purged yet, at most [limit]. */
    fun superseded(
        batchId: UUID,
        limit: Int,
    ): List<UUID> =
        tenantDsl
            .current()
            .select(IMPORTS_STAGED.VERSION_ID)
            .from(IMPORTS_STAGED)
            .where(IMPORTS_STAGED.BATCH_ID.eq(batchId), IMPORTS_STAGED.PURGED_AT.isNull, IMPORTS_STAGED.SUPERSEDED_AT.isNotNull)
            .orderBy(IMPORTS_STAGED.STAGED_AT)
            .limit(limit)
            .fetch(IMPORTS_STAGED.VERSION_ID)
            .filterNotNull()

    fun purged(versionId: UUID) {
        tenantDsl
            .current()
            .update(IMPORTS_STAGED)
            .set(IMPORTS_STAGED.PURGED_AT, now())
            .where(IMPORTS_STAGED.VERSION_ID.eq(versionId), IMPORTS_STAGED.PURGED_AT.isNull)
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
