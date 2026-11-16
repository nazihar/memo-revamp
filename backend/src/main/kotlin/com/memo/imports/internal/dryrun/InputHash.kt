package com.memo.imports.internal.dryrun

import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.ImportMappings
import com.memo.imports.internal.IntakeState
import com.memo.jooq.tables.references.IMPORTS_FILE
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The input hash (design E06 §5.2): SHA-256 over the batch's settings, its source versions and their hashes, the RECEIVED
 * originals (path and SHA-256, in path order) and the mappings (kind, value, order, target, confirmed). Any change to
 * them changes it; the start (T7) compares it with the dry run's. Reads only, inside a tenant transaction.
 */
@Component
class InputHash(
    private val files: Files,
    private val mappings: ImportMappingStore,
    private val tenantDsl: TenantDsl,
) {
    fun of(batch: ImportBatch): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun put(vararg parts: Any?) = digest.update((parts.joinToString(UNIT) { it?.toString().orEmpty() } + RECORD).toByteArray())
        val s = batch.settings
        put("settings", s.sourceCode, s.timeZone.id, s.defaultClassificationId, s.numberPrefix)
        batch.sources.sortedBy { it.table }.forEach { put("source", it.table, it.versionId, files.version(it.versionId)?.sha256) }
        var after: String? = null
        do {
            val page = received(batch, after)
            page.forEach { (path, sha256) -> put("original", path, sha256) }
            after = page.lastOrNull()?.first
        } while (page.size == PAGE)
        put("mappings", ImportMappings.hash(mappings.all(batch.id)))
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun received(
        batch: ImportBatch,
        after: String?,
    ): List<Pair<String, String>> =
        tenantDsl
            .current()
            .select(IMPORTS_FILE.PATH, IMPORTS_FILE.SHA256)
            .from(IMPORTS_FILE)
            .where(IMPORTS_FILE.BATCH_ID.eq(batch.id), IMPORTS_FILE.STATE.eq(IntakeState.RECEIVED.name))
            .and(
                after?.let { IMPORTS_FILE.PATH.gt(it) } ?: org.jooq.impl.DSL
                    .noCondition(),
            ).orderBy(IMPORTS_FILE.PATH)
            .limit(PAGE)
            .fetch { it.value1()!! to HexFormat.of().formatHex(it.value2()!!) }

    private companion object {
        const val PAGE = 5_000
        const val UNIT = "\u001f"
        const val RECORD = "\u001e"
    }
}
