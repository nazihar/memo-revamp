package com.memo.imports.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserSummaries
import com.memo.memo.LegacyMemos
import com.memo.org.OrgUnitDirectory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * A batch's mappings (ADR-0055, design E06 §6): listed with their row counts and proposals, and confirmed or changed by
 * a holder of `memo.import` (the controller checks the permission), one kind at a time, on the batch's version. Saving
 * sends a VALIDATED batch back to OPEN: the dry run must run again. Each target must exist in the tenant, and a
 * classification mapping must keep the legacy order (`CLASSIFICATION_ORDER`). Inside a tenant transaction.
 */
@Service
class ImportMappings(
    private val store: ImportMappingStore,
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val legacy: LegacyMemos,
    private val clearances: Clearances,
    private val units: OrgUnitDirectory,
    private val users: UserSummaries,
    private val audit: AuditLog,
) {
    fun list(
        batchId: UUID,
        kind: MappingKind?,
    ): List<ImportMapping> {
        if (batches.find(batchId) == null) throw ImportBatchNotFoundException()
        return store.all(batchId, kind)
    }

    /** Saves [changes] to [kind]'s mappings of the batch at [version]; returns the batch's new version. */
    fun save(
        batchId: UUID,
        version: Long,
        kind: MappingKind,
        changes: List<MappingChange>,
    ): Long {
        locked(batchId, version)
        val before = store.all(batchId, kind).associateBy { it.legacyValue }
        val problems = checked(kind, changes, before)
        if (problems.isNotEmpty()) throw ImportRejectedException(problems.take(MAX_PROBLEMS))
        store.apply(batchId, kind, changes, ImportBatches.user())
        // The batch row is locked at this version, so the guarded update cannot miss.
        check(transitions.move(batchId, ImportBatchTransitions.EDITABLE, BatchState.OPEN, version)) { "batch $batchId changed" }
        val after = store.all(batchId, kind)
        val changed = changes.map { it.legacyValue }.toSet()
        audit.record(
            "import.mapping.changed",
            ImportBatches.OBJECT_TYPE,
            batchId.toString(),
            before = mapOf("kind" to kind.name, "entries" to before.values.filter { it.legacyValue in changed }.audited()),
            after =
                mapOf(
                    "kind" to kind.name,
                    "changed" to changed.size,
                    "entries" to after.filter { it.legacyValue in changed }.audited(),
                    "hash" to hash(after),
                ),
        )
        return version + 1
    }

    /** The batch, locked, editable and at [version] (409 `WRONG_STATE`, 412 otherwise). */
    private fun locked(
        batchId: UUID,
        version: Long,
    ) {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(!batch.state.editable, "WRONG_STATE") { "Mappings can change only before the import starts." }
        if (batch.version != version) throw ConcurrentModificationException("import batch $batchId", version)
    }

    private fun checked(
        kind: MappingKind,
        changes: List<MappingChange>,
        known: Map<String, ImportMapping>,
    ): List<FieldProblem> {
        val targets = Targets(kind, changes.take(MAX_CHANGES).mapNotNull { it.target })
        val problems = mutableListOf<FieldProblem>()
        if (changes.isEmpty() || changes.size > MAX_CHANGES) {
            problems += FieldProblem("mappings", "COUNT", "Send 1 to $MAX_CHANGES mappings at a time.")
        }
        val seen = mutableSetOf<String>()
        changes.take(MAX_CHANGES).forEachIndexed { i, change ->
            val at = "mappings[$i]"
            val target = change.target
            when {
                change.legacyValue !in known -> problems += invalid("$at.legacyValue", "No such legacy value in this batch.")
                !seen.add(change.legacyValue) -> problems += invalid("$at.legacyValue", "The same value is listed twice.")
                target != null && !targets.valid(target) -> problems += invalid("$at.target", "No such target.")
                else -> problems += orderProblems(kind, change, at)
            }
        }
        if (problems.isEmpty() && kind == MappingKind.CLASSIFICATION) problems += order(changes, known, targets)
        return problems
    }

    private fun orderProblems(
        kind: MappingKind,
        change: MappingChange,
        at: String,
    ): List<FieldProblem> {
        val order = change.legacyOrder
        return listOfNotNull(
            invalid("$at.legacyOrder", "Only classification mappings have an order.").takeIf {
                order != null && kind != MappingKind.CLASSIFICATION
            },
            invalid("$at.legacyOrder", "An order of 1 or more.").takeIf { order != null && order < 1 },
            invalid("$at.legacyOrder", "Give the legacy marking's order (1 = least strict).").takeIf {
                kind == MappingKind.CLASSIFICATION && change.confirmed && change.target != null && order == null
            },
        )
    }

    /**
     * The classification mappings, with [changes] applied, keep the legacy order: a stricter legacy marking (a higher
     * order) never maps to a lower level than a less strict one.
     */
    private fun order(
        changes: List<MappingChange>,
        known: Map<String, ImportMapping>,
        targets: Targets,
    ): List<FieldProblem> {
        val merged =
            known.mapValues { (_, m) -> m.legacyOrder to m.target } + changes.associate { it.legacyValue to (it.legacyOrder to it.target) }
        val ranked =
            merged.mapNotNull { (value, mapping) ->
                val (order, target) = mapping
                val rank = target?.let(targets::rank)
                if (order != null && rank != null) Triple(value, order, rank) else null
            }
        val broken =
            ranked.filter { (_, order, rank) -> ranked.any { (_, other, otherRank) -> other < order && otherRank > rank } }.map { it.first }
        return broken.sorted().take(MAX_PROBLEMS).map { value ->
            val i = changes.indexOfFirst { it.legacyValue == value }
            FieldProblem(
                if (i >= 0) "mappings[$i].target" else "mappings",
                "CLASSIFICATION_ORDER",
                "A stricter legacy marking cannot map to a lower level than a less strict one.",
            )
        }
    }

    /** The tenant's targets of one kind among [given] (looked up once). */
    private inner class Targets(
        private val kind: MappingKind,
        given: Collection<String>,
    ) {
        private val levels by lazy { clearances.levels().associate { it.id.toString() to it.rank } }
        private val known: Set<String> by lazy {
            val ids = given.mapNotNull(ImportIntake::uuidOrNull).toSet()
            when (kind) {
                MappingKind.STATUS -> {
                    StatusTarget.entries.map { it.name }.toSet()
                }

                MappingKind.TYPE -> {
                    legacy.types().map { it.id.toString() }.toSet()
                }

                MappingKind.CLASSIFICATION -> {
                    levels.keys
                }

                MappingKind.UNIT -> {
                    units
                        .units(ids)
                        .keys
                        .map { it.toString() }
                        .toSet()
                }

                MappingKind.PERSON -> {
                    users
                        .of(ids)
                        .keys
                        .map { it.toString() }
                        .toSet()
                }
            }
        }

        fun valid(target: String): Boolean = target in known

        fun rank(target: String): Int? = levels[target]
    }

    private fun invalid(
        field: String,
        message: String,
    ) = FieldProblem(field, "MAPPING_INVALID", message)

    private fun List<ImportMapping>.audited(): List<Map<String, Any?>> =
        take(MAX_AUDITED).map {
            mapOf("value" to it.legacyValue, "target" to it.target, "order" to it.legacyOrder, "confirmed" to it.confirmed)
        }

    companion object {
        const val MAX_CHANGES = 5_000
        private const val MAX_PROBLEMS = 100
        private const val MAX_AUDITED = 200

        /** SHA-256 (hex) of mappings as the dry run reads them: kind, value, order, target and whether confirmed, in key order. */
        fun hash(mappings: List<ImportMapping>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            mappings.sortedWith(compareBy({ it.kind }, { it.legacyValue })).forEach {
                digest.update(
                    "${it.kind}\u0000${it.legacyValue}\u0000${it.legacyOrder}\u0000${it.target}\u0000${it.confirmed}\n".toByteArray(),
                )
            }
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
