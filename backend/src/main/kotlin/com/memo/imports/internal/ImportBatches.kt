package com.memo.imports.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** Settings as a request gives them, before they are checked. */
data class BatchSettingsInput(
    val name: String?,
    val sourceCode: String?,
    val timeZone: String?,
    val defaultClassificationId: UUID? = null,
    val numberPrefix: String? = null,
)

/**
 * Import batches (ADR-0055, design E06 §4.1, §10): created, viewed, listed and changed by holders of `memo.import` (the
 * controller checks the permission). At most [MAX_COUNTED] per tenant may be other than CLOSED or ABANDONED. Settings
 * change only while the batch is OPEN or VALIDATED, and a change sends it back to OPEN. Inside a tenant transaction.
 */
@Service
class ImportBatches(
    private val store: ImportBatchStore,
    private val clearances: Clearances,
    private val audit: AuditLog,
    private val ids: IdGenerator,
) {
    fun create(input: BatchSettingsInput): ImportBatch {
        val settings = checked(input)
        refuseIf(store.countCounted() >= MAX_COUNTED, "TOO_MANY_BATCHES") {
            "A tenant can have at most $MAX_COUNTED batches that are not closed or abandoned."
        }
        val id = ids.next()
        store.insert(id, settings, user())
        audit.record("import.batch.created", OBJECT_TYPE, id.toString(), after = audited(settings))
        return get(id)
    }

    fun update(
        id: UUID,
        version: Long,
        input: BatchSettingsInput,
    ): ImportBatch {
        val before = get(id)
        val settings = checked(input)
        refuseIf(!before.state.editable, "WRONG_STATE") { "A batch's settings can change only before its import starts." }
        if (!store.updateSettings(id, version, settings)) throw ConcurrentModificationException("import batch $id", version)
        audit.record("import.batch.changed", OBJECT_TYPE, id.toString(), before = audited(before.settings), after = audited(settings))
        return get(id)
    }

    fun get(id: UUID): ImportBatch = store.find(id) ?: throw ImportBatchNotFoundException()

    fun page(
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<ImportBatch> = store.page(after, limit)

    private fun checked(input: BatchSettingsInput): BatchSettings {
        val name = input.name?.trim().orEmpty()
        val code =
            input.sourceCode
                ?.trim()
                ?.uppercase(Locale.ROOT)
                .orEmpty()
        val zone = input.timeZone?.trim()?.takeIf { it in ZoneId.getAvailableZoneIds() }
        val prefix = input.numberPrefix?.trim()?.takeIf { it.isNotEmpty() }
        val level = input.defaultClassificationId
        val problems =
            listOfNotNull(
                FieldProblem("name", "REQUIRED", "Enter a name.").takeIf { name.isEmpty() },
                FieldProblem("name", "TOO_LONG", "At most $MAX_NAME characters.").takeIf { name.length > MAX_NAME },
                FieldProblem("sourceCode", "SOURCE_CODE_INVALID", "1 to 20 capital letters, digits or hyphens.")
                    .takeUnless { SOURCE_CODE.matches(code) },
                FieldProblem("timeZone", "TIME_ZONE_INVALID", "A time zone such as Asia/Dhaka.").takeIf { zone == null },
                FieldProblem("numberPrefix", "NUMBER_PREFIX_INVALID", "1 to 20 letters, digits, '/', '.', '_' or '-'.")
                    .takeIf { prefix != null && !NUMBER_PREFIX.matches(prefix) },
                FieldProblem("defaultClassificationId", "unknown", "No such classification level.")
                    .takeIf { level != null && clearances.level(level) == null },
            )
        if (problems.isNotEmpty() || zone == null) throw ImportRejectedException(problems)
        return BatchSettings(name, code, ZoneId.of(zone), level, prefix)
    }

    private fun audited(settings: BatchSettings): Map<String, Any?> =
        mapOf(
            "name" to settings.name,
            "sourceCode" to settings.sourceCode,
            "timeZone" to settings.timeZone.id,
            "defaultClassificationId" to settings.defaultClassificationId?.toString(),
            "numberPrefix" to settings.numberPrefix,
        )

    companion object {
        const val OBJECT_TYPE = "import.batch"
        const val MAX_COUNTED = 5
        const val MAX_NAME = 200
        private val SOURCE_CODE = Regex("^[A-Z0-9][A-Z0-9-]{0,19}$")
        private val NUMBER_PREFIX = Regex("^[A-Za-z0-9][A-Za-z0-9/._-]{0,19}$")

        /** The signed-in user acting (the controller only lets users in). */
        fun user(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "an import needs a signed-in user" }
    }
}
