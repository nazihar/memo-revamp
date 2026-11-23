package com.memo.actions.internal

import com.memo.org.internal.OrgCalendar
import com.memo.tenancy.TenantSetting
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.MutableClock
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The action taken report's sections around the tenant's midnight (design E07 §1.12; the boundary E07 T9 found): an action
 * is overdue from the day after its due date on the tenant's calendar (`OrgCalendar`, the organisation's zone), never by
 * the database's `current_date` or UTC's date. With the clock pinned at 23:30 and 00:30 in a UTC+6 tenant's zone, and at
 * 18:30 UTC when that tenant is already on the next day, an action due on the tenant's yesterday is overdue and one due
 * on its today is open; a tenant without a zone keeps UTC's day.
 */
class ReportSectionsDayBoundaryTest {
    private fun calendar(
        zone: String?,
        clock: MutableClock,
    ): OrgCalendar {
        val settings = mockk<TenantSettings>()
        every { settings.get(OrgCalendar.TIME_ZONE) } returns zone?.let { TenantSetting(OrgCalendar.TIME_ZONE, it, 1) }
        return OrgCalendar(settings, clock)
    }

    private fun action(due: LocalDate): ActionRow {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        return ActionRow(
            id = UUID.randomUUID(),
            forumId = UUID.randomUUID(),
            numberYear = 2026,
            sequence = 1,
            reference = "BRC A1/2026",
            source = ActionSource.DIRECT,
            meetingId = null,
            itemId = null,
            decisionId = null,
            decisionSeq = null,
            conditionIndex = null,
            sourceNote = null,
            sourceLabel = "Board Risk Committee",
            item = null,
            title = "Report the revised collateral valuation",
            description = null,
            context = null,
            classificationId = UUID.randomUUID(),
            owner = ActionOwner.person(UUID.randomUUID()),
            holderUserId = null,
            ownerStep = null,
            priority = ActionPriority.NORMAL,
            dueDate = due,
            originalDueDate = due,
            dueRevisions = 0,
            amberFrom = null,
            status = ActionStatus.OPEN,
            workflowInstanceId = null,
            stepName = null,
            doneAt = null,
            closedAt = null,
            cancelKind = null,
            reopenCount = 0,
            createdBy = UUID.randomUUID(),
            createdAt = now,
            issuedBy = UUID.randomUUID(),
            issuedAt = now,
            version = 1,
        )
    }

    @ParameterizedTest(name = "{0} in {1}: the tenant''s today is {2}")
    @CsvSource(
        // 23:30 in Dhaka on the 23rd (17:30 UTC): still the 23rd.
        "2026-11-23T17:30:00Z, Asia/Dhaka, 2026-11-23",
        // 00:30 in Dhaka on the 24th (18:30 UTC on the 23rd): the tenant is on the 24th, UTC is not.
        "2026-11-23T18:30:00Z, Asia/Dhaka, 2026-11-24",
        // 23:30 UTC on the 23rd: 05:30 on the 24th in Dhaka.
        "2026-11-23T23:30:00Z, Asia/Dhaka, 2026-11-24",
        // 00:30 in Dhaka on the 23rd (18:30 UTC on the 22nd).
        "2026-11-22T18:30:00Z, Asia/Dhaka, 2026-11-23",
        // A tenant without a zone keeps UTC's day at 18:30 UTC.
        "2026-11-23T18:30:00Z, , 2026-11-23",
    )
    fun `an action due on the tenant's yesterday is overdue, one due on its today is open`(
        at: Instant,
        zone: String?,
        tenantToday: LocalDate,
    ) {
        val today = calendar(zone, MutableClock(at)).today()
        assertThat(today).isEqualTo(tenantToday)

        val yesterday = action(tenantToday.minusDays(1))
        val dueToday = action(tenantToday)
        val sections = ReportSections.of(listOf(dueToday, yesterday), today)

        assertThat(sections.getValue(ActionReportSection.OVERDUE)).containsExactly(yesterday)
        assertThat(sections.getValue(ActionReportSection.OPEN)).containsExactly(dueToday)
        // The colour agrees: past its date is red, due today is not.
        assertThat(RagRule.word(yesterday.ragFacts, today)).isEqualTo(RagWord.OVERDUE)
        assertThat(RagRule.word(dueToday.ragFacts, today)).isNotEqualTo(RagWord.OVERDUE)
    }
}
