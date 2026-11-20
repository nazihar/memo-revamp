package com.memo.meeting

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.attendance.Attendance
import com.memo.meeting.internal.attendance.AttendanceReads
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tenant isolation of the attendance register (test plan E05 T4, `MeetingCrossTenantIT`'s attendance tables, kept apart to
 * keep that class small; AGENTS.md §3.2): another tenant neither reads nor writes a tenant's register, events, item in
 * progress or quorum changes, through the services or SQL; and within a tenant the events never change.
 */
class AttendanceCrossTenantIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var attendance: Attendance

    @Autowired lateinit var attendanceReads: AttendanceReads

    private class Side(
        val tenant: TestTenant,
        val admin: UserAccount,
        val member: UserAccount,
        val forum: UUID,
    )

    @Test
    fun `another tenant neither sees nor writes the register, its events or the quorum, and the events never change`() {
        val a = side("attenda")
        val b = side("attendb")
        val ofA = meeting(a)
        val apologies =
            com.memo.meeting.internal.attendance
                .AttendanceInput(a.member.id.value, AttendanceEventKind.APOLOGIES)
        asUser(a.tenant, a.admin) { attendance.record(ofA, null, apologies) }
        asUser(a.tenant, a.admin) { reads.get(ofA) }.let { asUser(a.tenant, a.admin) { lifecycle.start(ofA, it.version) } }

        // Through the services: B's administrator gets 404 for A's register, quorum, changes, events and item in progress.
        listOf<() -> Any>(
            { attendanceReads.register(ofA) },
            { attendanceReads.quorum(ofA, null) },
            { attendanceReads.changes(ofA) },
            { attendance.record(ofA, null, apologies.copy(userId = b.member.id.value)) },
            { attendance.arrivals(ofA, listOf(b.member.id.value), AttendanceMode.IN_PERSON, null) },
            { attendance.progress(ofA, UUID.randomUUID()) },
        ).forEach { call -> assertThatThrownBy { asUser(b.tenant, b.admin) { call() } }.isInstanceOf(MeetingNotFoundException::class.java) }

        // Through SQL as the app role: none of A's rows is visible to B, none written for A or pointing at A's meeting.
        val tables = listOf("meeting_attendance", "meeting_attendance_event", "meeting_item_progress", "meeting_quorum_change")
        AppRoleJdbc.inTransaction(a.tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_attendance_event where meeting_id = ?", ofA)).isEqualTo(1)
        }
        AppRoleJdbc.inTransaction(b.tenant.id) { c ->
            tables.forEach { table ->
                assertThat(c.long("select count(*) from $table where tenant_id = ?", a.tenant.id.value)).describedAs(table).isZero()
            }
            assertThat(c.update("update meeting_attendance set state = 'ABSENT', version = version + 1 where meeting_id = ?", ofA)).isZero()
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val writes =
            listOf(
                "insert into meeting_attendance (tenant_id, meeting_id, user_id, capacity, state, opened_at, updated_at) " +
                    "values (?, ?, ?, 'GUEST', 'EXPECTED', ?, ?)" to listOf<Any>(a.tenant.id.value, ofA, b.member.id.value, now, now),
                "insert into meeting_attendance (tenant_id, meeting_id, user_id, capacity, state, opened_at, updated_at) " +
                    "values (?, ?, ?, 'GUEST', 'EXPECTED', ?, ?)" to listOf<Any>(b.tenant.id.value, ofA, b.member.id.value, now, now),
                "insert into meeting_item_progress (id, tenant_id, meeting_id, item_id, started_at, recorded_at) " +
                    "values (?, ?, ?, ?, ?, ?)" to
                    listOf<Any>(UUID.randomUUID(), b.tenant.id.value, ofA, UUID.randomUUID(), now, now),
            )
        writes.forEach { (sql, params) ->
            val written = runCatching { AppRoleJdbc.inTransaction(b.tenant.id) { c -> c.update(sql, *params.toTypedArray()) } }
            assertThat(written.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }

        // In A itself: events, the item in progress and the quorum's changes never change, nothing is deleted, keys stay.
        listOf(
            "update meeting_attendance_event set kind = 'ABSENT' where meeting_id = ?",
            "delete from meeting_attendance_event where meeting_id = ?",
            "delete from meeting_attendance where meeting_id = ?",
            "update meeting_attendance set capacity = 'GUEST', version = version + 1 where meeting_id = ?",
            "update meeting_attendance set state = 'ABSENT' where meeting_id = ?",
            "delete from meeting_item_progress where meeting_id = ?",
            "update meeting_quorum_change set kind = 'LOST' where meeting_id = ?",
        ).forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(a.tenant.id) { c -> c.update(sql, ofA) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
        }
    }

    private fun side(label: String): Side {
        val tenant = registry.aTestTenant(transactions, settings, label, mfaRequired = false)
        val admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val member = accounts.anActiveUser(transactions, tenant, aPassword())
        val forum =
            asUser(tenant, admin) {
                val type = types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true).type.id
                forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)).id
            }
        asUser(tenant, admin) { members.add(forum, MemberInput(userId = member.id.value, role = ForumRole.MEMBER)) }
        return Side(tenant, admin, member, forum)
    }

    private fun meeting(side: Side): UUID {
        val day = LocalDate.now(ZoneOffset.UTC).plusDays(20).atTime(LocalTime.of(10, 0))
        return asUser(side.tenant, side.admin) {
            val created =
                lifecycle.create(
                    side.forum,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day, day.plusHours(2), "Asia/Dhaka"),
                )
            lifecycle.schedule(created.id, created.version).id
        }
    }

    private fun <T> asUser(
        tenant: TestTenant,
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
