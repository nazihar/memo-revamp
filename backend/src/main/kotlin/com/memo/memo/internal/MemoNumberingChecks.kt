package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What a memo type's numbering must satisfy beyond its own format (ADR-0044): its numbers can never equal those of another
 * series (`SERIES_OVERLAP`), and types that share a series restart and count the same way, since they share its counters
 * (`SERIES_MISMATCH`). Checked against the other types' published versions. Inside a tenant transaction.
 */
@Component
class MemoNumberingChecks(
    private val versions: MemoTypeVersionStore,
) {
    fun problems(
        settings: MemoTypeSettings,
        typeId: UUID?,
    ): List<FieldProblem> {
        val numbering = settings.numbering
        val own = NumberFormats.problems(numbering)
        if (own.isNotEmpty()) return own
        val others = versions.publishedAll().filter { it.typeId != typeId }
        val skeleton = NumberFormats.skeleton(numbering, settings.code)
        val overlap =
            others.firstOrNull {
                it.settings.numbering.series != numbering.series &&
                    NumberFormats.skeleton(it.settings.numbering, it.settings.code) == skeleton
            }
        val mismatch =
            others.firstOrNull { other ->
                val theirs = other.settings.numbering
                theirs.series == numbering.series &&
                    (
                        theirs.reset != numbering.reset ||
                            theirs.countPer != numbering.countPer ||
                            (numbering.reset == NumberReset.FINANCIAL_YEAR && theirs.fyStartMonth != numbering.fyStartMonth)
                    )
            }
        return listOfNotNull(
            overlap?.let {
                FieldProblem(
                    "numbering/parts",
                    "SERIES_OVERLAP",
                    "${it.settings.name} (series ${it.settings.numbering.series}) can make the same numbers; " +
                        "add the type code or other text, or use its series.",
                )
            },
            mismatch?.let {
                FieldProblem(
                    "numbering/series",
                    "SERIES_MISMATCH",
                    "${it.settings.name} shares this series: restart and count the same way, or use another series.",
                )
            },
        )
    }
}
