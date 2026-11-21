package com.memo.minutes.internal

import com.memo.forum.OutcomeKind
import com.memo.minutes.internal.ReleaseMapping.Released
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * The release mapping (test plan E06 T4; ADR-0065's table, design E06 §1.4): each outcome kind to what the submission
 * gets, a pass-through submission noted whatever the outcome, and the remark with the label, the number, the text and the
 * conditions, cut at 4,000 characters.
 */
class ReleaseMappingTest {
    private val conditions = listOf("Security perfected before disbursement.", "Quarterly stock reports.")

    private fun of(
        kind: OutcomeKind,
        text: String? = "It was RESOLVED that the loan be approved.",
        conditions: List<String> = emptyList(),
    ) = Released(kind, label(kind), text, conditions)

    private fun label(kind: OutcomeKind) =
        kind.name
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar(Char::uppercase)

    @Test
    fun `approved, with conditions, noted, rejected and returned are decided with that outcome`() {
        listOf(
            OutcomeKind.APPROVED,
            OutcomeKind.APPROVED_WITH_CONDITIONS,
            OutcomeKind.NOTED,
            OutcomeKind.REJECTED,
            OutcomeKind.RETURNED,
        ).forEach { kind ->
            val command = ReleaseMapping.command(of(kind), "BRC/2026/014", waits = true, meetingLabel = "BRC 3/2026")
            assertThat(command).isEqualTo(
                ReleaseCommand.Decide(kind, "${label(kind)}. Resolution BRC/2026/014: It was RESOLVED that the loan be approved."),
            )
            assertThat(command.kind).isEqualTo(kind)
        }
    }

    @Test
    fun `a withdrawal is returned to the initiator, saying it was withdrawn at the meeting`() {
        val command = ReleaseMapping.command(of(OutcomeKind.WITHDRAWN, "The presenter withdrew it."), null, true, "BRC 3/2026")
        assertThat(command).isEqualTo(
            ReleaseCommand.Decide(OutcomeKind.RETURNED, "Withdrawn. Withdrawn at the meeting. The presenter withdrew it."),
        )
        assertThat(command.kind).isEqualTo(OutcomeKind.RETURNED)
    }

    @Test
    fun `a deferral goes back to the tray with its target, a referral to the other committee`() {
        val date = LocalDate.of(2027, 1, 15)
        val deferred =
            ReleaseMapping.command(
                of(OutcomeKind.DEFERRED, "More data needed.").copy(deferDate = date),
                null,
                true,
                "BRC 3/2026",
            )
        assertThat(deferred).isEqualTo(ReleaseCommand.Defer("Deferred at BRC 3/2026. More data needed.", date))
        assertThat(deferred.kind).isEqualTo(OutcomeKind.DEFERRED)
        val toNext = ReleaseMapping.command(of(OutcomeKind.DEFERRED, null), null, true, null)
        assertThat(toNext).isEqualTo(ReleaseCommand.Defer("Deferred at the meeting.", null))

        val other = UUID.randomUUID()
        val referred = of(OutcomeKind.REFERRED).copy(referToForumId = other, referReason = "An audit matter")
        assertThat(ReleaseMapping.command(referred, null, true, "BRC 3/2026")).isEqualTo(ReleaseCommand.Refer(other, "An audit matter"))
        assertThat(ReleaseMapping.command(referred.copy(referReason = " "), null, true, null))
            .isEqualTo(ReleaseCommand.Refer(other, "Referred."))
        assertThat(ReleaseMapping.command(referred, null, true, null).kind).isEqualTo(OutcomeKind.REFERRED)
        assertThatThrownBy { ReleaseMapping.command(of(OutcomeKind.REFERRED), null, true, null) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a submission whose memo does not wait is noted, whatever the outcome`() {
        OutcomeKind.entries.forEach { kind ->
            val command = ReleaseMapping.command(of(kind).copy(referToForumId = UUID.randomUUID()), "BRC/2026/001", false, null)
            assertThat(command).isInstanceOf(ReleaseCommand.Decide::class.java)
            assertThat(command.kind).isEqualTo(OutcomeKind.NOTED)
        }
    }

    @Test
    fun `the remark carries the label, the number, the text and the numbered conditions`() {
        val withAll = of(OutcomeKind.APPROVED_WITH_CONDITIONS, conditions = conditions)
        assertThat(ReleaseMapping.remark(withAll, "BRC/2026/014")).isEqualTo(
            "Approved with conditions. Resolution BRC/2026/014: It was RESOLVED that the loan be approved. " +
                "Conditions: 1. Security perfected before disbursement. 2. Quarterly stock reports.",
        )
        assertThat(ReleaseMapping.remark(of(OutcomeKind.NOTED, "Noted."), null)).isEqualTo("Noted. Noted.")
        assertThat(ReleaseMapping.remark(of(OutcomeKind.NOTED, null), null)).isEqualTo("Noted.")
        assertThat(ReleaseMapping.remark(of(OutcomeKind.APPROVED, null), "BRC/2026/001")).isEqualTo("Approved. Resolution BRC/2026/001:")
    }

    @Test
    fun `a remark longer than the workflow takes is cut, and says where the rest is`() {
        val long = of(OutcomeKind.APPROVED, "x".repeat(3990), conditions = conditions)
        val remark = ReleaseMapping.remark(long, "BRC/2026/014")
        assertThat(remark).hasSize(ReleaseMapping.MAX_REMARK).endsWith(" (see the resolution)")
        assertThat(ReleaseMapping.cut("short", 10)).isEqualTo("short")
        assertThat(ReleaseMapping.cut("a".repeat(10), 10)).isEqualTo("a".repeat(10))
        assertThat(ReleaseMapping.cut("a".repeat(11), 30)).isEqualTo("a".repeat(11))
        val cut = ReleaseMapping.cut("a".repeat(40), 30)
        assertThat(cut).hasSize(30).isEqualTo("a".repeat(9) + " (see the resolution)")
        val deferred = ReleaseMapping.command(of(OutcomeKind.DEFERRED, "y".repeat(2500)), null, true, "BRC 3/2026")
        assertThat((deferred as ReleaseCommand.Defer).reason).hasSize(ReleaseMapping.MAX_REASON)
    }
}
