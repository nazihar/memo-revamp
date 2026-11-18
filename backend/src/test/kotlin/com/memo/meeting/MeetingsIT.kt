package com.memo.meeting

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.internal.EndReason
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMemberRow
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.org.OrgDirectory
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Meetings (test plan E03, T1; ADR-0060): a committee's secretary creates a draft only officers see, schedules it for its
 * audience, reschedules, starts, ends or cancels it along the state machine; guests join the audience; the audience
 * follows the roster and the meeting's date; and who sees what follows design E03 §4. Every change is audited.
 */
class MeetingsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var meetings: Meetings

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var org: OrgDirectory

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var chair: UserAccount
    private lateinit var director: UserAccount
    private lateinit var reader: UserAccount
    private lateinit var user: UserAccount
    private lateinit var otherSecretary: UserAccount
    private lateinit var typeId: UUID
    private lateinit var brc: UUID
    private lateinit var alco: UUID
    private lateinit var directorSeat: ForumMemberRow
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "meetings", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        chair = accounts.anActiveUser(transactions, tenant, aPassword())
        director = accounts.anActiveUser(transactions, tenant, aPassword())
        reader = accounts.anActiveUser(transactions, tenant, aPassword())
        user = accounts.anActiveUser(transactions, tenant, aPassword())
        otherSecretary = accounts.anActiveUser(transactions, tenant, aPassword())
        typeId = asUser(admin) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", typeId)) }.id
        alco = asUser(admin) { forums.create(ForumInput("alco", "ALCO", "Asset and Liability Committee", "ALCO", typeId)) }.id
        add(brc, secretary, ForumRole.SECRETARY)
        add(brc, chair, ForumRole.CHAIR)
        directorSeat = add(brc, director, ForumRole.MEMBER).row
        add(alco, otherSecretary, ForumRole.SECRETARY)
        asUser(admin) { access.grantCustom(reader.id.value, "Committee readers", setOf(Permission.FORUM_READ)) }
    }

    @Test
    fun `a secretary creates a draft only officers see, and scheduling shows it to the audience`() {
        val day = today.plusDays(24)
        assertThat(problem(secretary) { create(day, link = null) }).isEqualTo("joinUrl:REQUIRED")

        val draft = asUser(secretary) { create(day) }
        assertThat(draft.status).isEqualTo(MeetingStatus.DRAFT)
        assertThat(draft.zone.id).isEqualTo("Asia/Dhaka")
        assertThat(draft.startsAt).isEqualTo(day.atTime(LocalTime.of(10, 0)).atZone(draft.zone).toInstant())
        Audit.assertAudited(tenant.id, "meeting.created", draft.id.toString(), secretary.id.value.toString())
        assertThat(asUser(chair) { reads.get(draft.id) }.relation).isEqualTo(MeetingRelation.OFFICER)
        assertThat(asUser(admin) { reads.get(draft.id) }.canManage).isTrue()
        for (outsider in listOf(director, user, reader, otherSecretary)) {
            assertThatThrownBy { asUser(outsider) { reads.get(draft.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        }
        assertThat(asUser(director) { reads.mine(null) }).isEmpty()
        assertThat(asUser(secretary) { reads.mine(null) }.map { it.id }).containsExactly(draft.id)

        val scheduled = asUser(secretary) { lifecycle.schedule(draft.id, draft.version) }
        assertThat(scheduled.status).isEqualTo(MeetingStatus.SCHEDULED)
        Audit.assertAudited(tenant.id, "meeting.scheduled", draft.id.toString(), secretary.id.value.toString())

        val seen = asUser(director) { reads.get(draft.id) }
        assertThat(seen.relation).isEqualTo(MeetingRelation.MEMBER)
        assertThat(seen.joinUrl).isEqualTo("https://meet.example/abc")
        assertThat(seen.canManage).isFalse()
        val mine = asUser(director) { reads.mine(null) }.single()
        assertThat(mine.id).isEqualTo(draft.id)
        assertThat(mine.joinUrl).isEqualTo("https://meet.example/abc")
        assertThatThrownBy { asUser(user) { reads.get(draft.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThat(asUser(user) { reads.mine(null) }).isEmpty()

        // forum.read sees the meeting without its place or link; nobody outside sees the committee's list.
        val read = asUser(reader) { reads.get(draft.id) }
        assertThat(read.relation).isEqualTo(MeetingRelation.READER)
        assertThat(listOf(read.joinUrl, read.location, read.joinDetails)).containsOnlyNulls()
        assertThat(asUser(reader) { reads.ofForum(brc, null, null, emptySet(), null) }.items.map { it.id }).containsExactly(draft.id)
        assertThat(asUser(director) { reads.ofForum(brc, null, null, emptySet(), null) }.items.single().joinUrl).isNotNull()
        assertThatThrownBy { asUser(user) { reads.ofForum(brc, null, null, emptySet(), null) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        assertThat(asUser(user) { meetings.find(draft.id) }?.status).isEqualTo(MeetingStatus.SCHEDULED)
    }

    @Test
    fun `only the committee's secretaries and forums manage run its meetings`() {
        val meeting = asUser(admin) { create(today.plusDays(20)) }
        Audit.assertAudited(tenant.id, "meeting.created", meeting.id.toString(), admin.id.value.toString())
        // The chair sees the forum and the draft but runs nothing; a member sees the forum only.
        assertThatThrownBy { asUser(chair) { create(today.plusDays(21)) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy {
            asUser(
                chair,
            ) { lifecycle.schedule(meeting.id, meeting.version) }
        }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(director) { create(today.plusDays(21)) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(director) { lifecycle.schedule(meeting.id, meeting.version) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        // Another committee's secretary and a plain user do not see the committee at all.
        for (outsider in listOf(otherSecretary, user)) {
            assertThatThrownBy { asUser(outsider) { create(today.plusDays(21)) } }.isInstanceOf(MeetingNotFoundException::class.java)
            assertThatThrownBy { asUser(outsider) { lifecycle.schedule(meeting.id, meeting.version) } }
                .isInstanceOf(MeetingNotFoundException::class.java)
        }
        assertThatThrownBy { asUser(reader) { create(today.plusDays(21)) } }.isInstanceOf(ForbiddenException::class.java)
        val other = asUser(otherSecretary) { create(today.plusDays(21), forum = alco) }
        assertThatThrownBy { asUser(secretary) { reads.get(other.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // A dissolved committee takes no new meetings.
        val forum = asUser(admin) { forums.get(alco) }
        asUser(admin) { forums.dissolve(alco, forum.version, null, "Merged into the board") }
        refused("FORUM_DISSOLVED") { asUser(otherSecretary) { create(today.plusDays(22), forum = alco) } }
        refused("FORUM_DISSOLVED") { asUser(otherSecretary) { lifecycle.schedule(other.id, other.version) } }
    }

    @Test
    fun `the state machine refuses what it does not allow, and reschedule and cancel need a reason`() {
        val draft = asUser(secretary) { create(today.plusDays(30)) }
        refused("WRONG_STATE") { asUser(secretary) { lifecycle.start(draft.id, draft.version) } }
        refused("WRONG_STATE") { asUser(secretary) { lifecycle.end(draft.id, draft.version) } }
        // A draft's times move without a reason.
        val moved = asUser(secretary) { lifecycle.reschedule(draft.id, draft.version, times(today.plusDays(31)), null) }
        Audit.assertAudited(tenant.id, "meeting.rescheduled", draft.id.toString(), secretary.id.value.toString())
        val scheduled = asUser(secretary) { lifecycle.schedule(draft.id, moved.version) }
        assertThatThrownBy { asUser(secretary) { lifecycle.schedule(draft.id, draft.version) } }
            .isInstanceOf(RefusedException::class.java)

        assertThat(problem(secretary) { lifecycle.reschedule(draft.id, scheduled.version, times(today.plusDays(32)), " ") })
            .isEqualTo("reason:REASON_REQUIRED")
        val rescheduled =
            asUser(secretary) { lifecycle.reschedule(draft.id, scheduled.version, times(today.plusDays(32)), "The chair is travelling") }
        assertThat(rescheduled.date).isEqualTo(today.plusDays(32))
        val entry = Audit.assertAudited(tenant.id, "meeting.rescheduled", draft.id.toString(), secretary.id.value.toString())
        assertThat(entry.canonical).contains("The chair is travelling")
        assertThatThrownBy { asUser(secretary) { lifecycle.start(draft.id, scheduled.version) } }
            .isInstanceOf(ConcurrentModificationException::class.java)

        assertThat(problem(secretary) { lifecycle.cancel(draft.id, rescheduled.version, null) }).isEqualTo("reason:REASON_REQUIRED")
        val started = asUser(secretary) { lifecycle.start(draft.id, rescheduled.version) }
        assertThat(started.status).isEqualTo(MeetingStatus.IN_PROGRESS)
        Audit.assertAudited(tenant.id, "meeting.started", draft.id.toString(), secretary.id.value.toString())
        refused("WRONG_STATE") { asUser(secretary) { lifecycle.cancel(draft.id, started.version, "Too late") } }
        refused("WRONG_STATE") { asUser(secretary) { lifecycle.edit(draft.id, started.version, input()) } }
        val held = asUser(secretary) { lifecycle.end(draft.id, started.version) }
        assertThat(held.status).isEqualTo(MeetingStatus.HELD)
        Audit.assertAudited(tenant.id, "meeting.ended", draft.id.toString(), secretary.id.value.toString())
        refused("WRONG_STATE") { asUser(secretary) { lifecycle.reschedule(draft.id, held.version, times(today.plusDays(40)), "Again") } }
        refused("WRONG_STATE") { asUser(secretary) { guests.add(draft.id, user.id.value, null) } }

        // A scheduled meeting called off: the reason is kept and the audience told; a draft is simply discarded.
        val second = asUser(secretary) { create(today.plusDays(33)).let { lifecycle.schedule(it.id, it.version) } }
        val cancelled = asUser(secretary) { lifecycle.cancel(second.id, second.version, "No quorum expected") }
        assertThat(cancelled.status to cancelled.statusReason).isEqualTo(MeetingStatus.CANCELLED to "No quorum expected")
        Audit.assertAudited(tenant.id, "meeting.cancelled", second.id.toString(), secretary.id.value.toString())
        assertThat(notified(director, "MEETING_CANCELLED")).isEqualTo(1)
        assertThat(notified(secretary, "MEETING_CANCELLED")).isZero()
        val third = asUser(secretary) { create(today.plusDays(34)) }
        assertThat(asUser(secretary) { lifecycle.cancel(third.id, third.version, "Duplicate") }.status).isEqualTo(MeetingStatus.CANCELLED)
        assertThat(notified(director, "MEETING_CANCELLED")).isEqualTo(1)
        // Members still see the cancelled meeting with its reason.
        assertThat(asUser(director) { reads.get(second.id) }.statusReason).isEqualTo("No quorum expected")
    }

    @Test
    fun `edits need the current version, and the classification never falls below the committee's`() {
        val meeting = asUser(secretary) { create(today.plusDays(20)) }
        val edited =
            asUser(secretary) {
                lifecycle.edit(meeting.id, meeting.version, input(MeetingMode.HYBRID, "Board room 3", kind = MeetingKind.SPECIAL))
            }
        assertThat(edited.kind to edited.mode).isEqualTo(MeetingKind.SPECIAL to MeetingMode.HYBRID)
        assertThat(edited.location).isEqualTo("Board room 3")
        Audit.assertAudited(tenant.id, "meeting.updated", meeting.id.toString(), secretary.id.value.toString())
        assertThatThrownBy { asUser(secretary) { lifecycle.edit(meeting.id, meeting.version, input()) } }
            .isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(problem(secretary) { lifecycle.edit(meeting.id, edited.version, input(MeetingMode.IN_PERSON, null)) })
            .isEqualTo("location:REQUIRED")
        assertThat(problem(secretary) { create(today.plusDays(21), link = "http://meet.example/abc") }).isEqualTo("joinUrl:LINK_NOT_HTTPS")
        assertThat(problem(secretary) { lifecycle.create(brc, input(), TimesInput(at(today), at(today).plusHours(1), "GMT+6")) })
            .isEqualTo("timeZone:UNKNOWN_ZONE")
        // No zone: the organisation's.
        val tenantZone = asUser(secretary) { lifecycle.create(brc, input(), TimesInput(at(today), at(today).plusHours(1), null)) }
        assertThat(tenantZone.zone).isEqualTo(asUser(admin) { org.zone() })

        val levels = asUser(admin) { clearances.levels() }
        assumeThat(levels.size).isGreaterThan(1)
        val top = levels.last()
        assertThat(problem(secretary) { lifecycle.edit(meeting.id, edited.version, input(classification = top.id)) })
            .isEqualTo("classificationId:CLASSIFICATION")
        asUser(admin) { access.ensureClearance(secretary.id.value, top.rank) }
        val secret = asUser(secretary) { lifecycle.edit(meeting.id, edited.version, input(classification = top.id)) }
        assertThat(secret.classificationId).isEqualTo(top.id)
        // Above the director's clearance, the meeting is gone for them even once scheduled.
        val scheduled = asUser(secretary) { lifecycle.schedule(meeting.id, secret.version) }
        assertThatThrownBy { asUser(director) { reads.get(scheduled.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(reader) { reads.get(scheduled.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        // A committee classified at the top level puts its meetings' floor there.
        val forum = asUser(admin) { forums.get(brc) }
        asUser(
            admin,
        ) { forums.update(brc, forum.version, ForumInput(forum.key, forum.code, forum.name, forum.shortName, typeId, null, top.id)) }
        assertThat(problem(secretary) { lifecycle.edit(meeting.id, scheduled.version, input(classification = levels.first().id)) })
            .isEqualTo("classificationId:CLASSIFICATION")
        assertThat(asUser(secretary) { create(today.plusDays(22)) }.classificationId).isEqualTo(top.id)
    }

    @Test
    fun `guests join the audience until removed, if they are cleared`() {
        val meeting = asUser(secretary) { create(today.plusDays(20)).let { lifecycle.schedule(it.id, it.version) } }
        val guest = user
        asUser(secretary) { guests.add(meeting.id, guest.id.value, "Chief Financial Officer") }
        Audit.assertAudited(tenant.id, "meeting.guest_added", meeting.id.toString(), secretary.id.value.toString())
        val seen = asUser(guest) { reads.get(meeting.id) }
        assertThat(seen.relation).isEqualTo(MeetingRelation.GUEST)
        assertThat(seen.guests.single().note).isEqualTo("Chief Financial Officer")
        assertThat(asUser(guest) { reads.mine(null) }.map { it.id }).containsExactly(meeting.id)
        refused("ALREADY_GUEST") { asUser(secretary) { guests.add(meeting.id, guest.id.value, null) } }
        assertThat(problem(secretary) { guests.add(meeting.id, UUID.randomUUID(), null) }).isEqualTo("userId:unknown")
        assertThatThrownBy {
            asUser(
                director,
            ) { guests.add(meeting.id, guest.id.value, null) }
        }.isInstanceOf(ForbiddenException::class.java)

        asUser(secretary) { guests.remove(meeting.id, guest.id.value) }
        Audit.assertAudited(tenant.id, "meeting.guest_removed", meeting.id.toString(), secretary.id.value.toString())
        assertThatThrownBy { asUser(guest) { reads.get(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { asUser(secretary) { guests.remove(meeting.id, guest.id.value) } }
            .isInstanceOf(MeetingNotFoundException::class.java)
        // Added again: a new row; the old one stays as history.
        asUser(secretary) { guests.add(meeting.id, guest.id.value, null) }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_guest where meeting_id = ?", meeting.id)).isEqualTo(2)
        }

        val levels = asUser(admin) { clearances.levels() }
        assumeThat(levels.size).isGreaterThan(1)
        asUser(admin) { access.ensureClearance(secretary.id.value, levels.last().rank) }
        val current = asUser(secretary) { reads.get(meeting.id) }
        asUser(secretary) { lifecycle.edit(meeting.id, current.version, input(classification = levels.last().id)) }
        assertThat(problem(secretary) { guests.add(meeting.id, reader.id.value, null) }).isEqualTo("userId:CLASSIFICATION")
    }

    @Test
    fun `the audience follows the roster on the meeting's date, and a held meeting keeps its own`() {
        val day = today.plusDays(30)
        val meeting = asUser(secretary) { create(day).let { lifecycle.schedule(it.id, it.version) } }
        val newcomer = accounts.anActiveUser(transactions, tenant, aPassword())
        val shortTerm = accounts.anActiveUser(transactions, tenant, aPassword())
        add(brc, newcomer, ForumRole.MEMBER)
        add(brc, shortTerm, ForumRole.STANDING_INVITEE, termEndsOn = today.plusDays(35))
        assertThat(asUser(newcomer) { reads.get(meeting.id) }.relation).isEqualTo(MeetingRelation.MEMBER)
        assertThat(audience(meeting.id, shortTerm)).isEqualTo("MEMBER:false:${today.plusDays(35)}")

        // Moved past the end of the short term: that seat leaves the audience.
        val moved = asUser(secretary) { lifecycle.reschedule(meeting.id, meeting.version, times(today.plusDays(40)), "Moved") }
        assertThat(audience(meeting.id, shortTerm)).isNull()
        assertThatThrownBy { asUser(shortTerm) { reads.get(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // A member who leaves before the meeting's date leaves its audience.
        val seat = asUser(admin) { members.add(brc, MemberInput(userId = user.id.value)) }.row
        assertThat(asUser(user) { reads.get(meeting.id) }.relation).isEqualTo(MeetingRelation.MEMBER)
        asUser(admin) { members.end(brc, seat.id, seat.version, today.plusDays(1), EndReason.RESIGNED, null) }
        assertThatThrownBy { asUser(user) { reads.get(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // Once held, the meeting keeps its audience: a former member keeps it in their history.
        val held = asUser(secretary) { lifecycle.start(meeting.id, moved.version).let { lifecycle.end(it.id, it.version) } }
        val newcomerSeat = asUser(admin) { members.add(brc, MemberInput(userId = reader.id.value)) }.row
        assertThat(audience(held.id, reader)).isNull()
        asUser(admin) { members.end(brc, directorSeat.id, directorSeat.version, today, EndReason.RESIGNED, null) }
        assertThat(asUser(director) { reads.get(held.id) }.relation).isEqualTo(MeetingRelation.MEMBER)
        assertThat(newcomerSeat.forumId).isEqualTo(brc)
    }

    private fun audience(
        meetingId: UUID,
        account: UserAccount,
    ): String? =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "select source, members_only, until from meeting_audience where meeting_id = ? and user_id = ? and removed_at is null",
                ).apply {
                    setObject(1, meetingId)
                    setObject(2, account.id.value)
                }.executeQuery()
                .use { rs -> if (rs.next()) "${rs.getString(1)}:${rs.getBoolean(2)}:${rs.getString(3)}" else null }
        }

    private fun notified(
        account: UserAccount,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", account.id.value, kind)
        }

    private fun add(
        forum: UUID,
        account: UserAccount,
        role: ForumRole,
        termEndsOn: LocalDate? = null,
    ) = asUser(admin) { members.add(forum, MemberInput(userId = account.id.value, role = role, termEndsOn = termEndsOn)) }

    private fun at(day: LocalDate) = day.atTime(LocalTime.of(10, 0))

    private fun times(day: LocalDate) = TimesInput(at(day), at(day).plusHours(3), "Asia/Dhaka")

    private fun input(
        mode: MeetingMode = MeetingMode.ONLINE,
        location: String? = null,
        link: String? = "https://meet.example/abc",
        kind: MeetingKind = MeetingKind.ORDINARY,
        classification: UUID? = null,
    ) = MeetingInput(kind, MeetingPlace(null, mode, location, link, "Meeting id 123"), classification)

    private fun create(
        day: LocalDate,
        link: String? = "https://meet.example/abc",
        forum: UUID = brc,
    ): MeetingRow = lifecycle.create(forum, input(link = link), times(day))

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(
        by: UserAccount,
        block: () -> Any,
    ): String {
        val rejected = runCatching { asUser(by, block) }.exceptionOrNull() as? MeetingRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private fun <T> asUser(
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
