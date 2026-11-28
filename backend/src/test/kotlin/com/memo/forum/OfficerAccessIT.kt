package com.memo.forum

import com.memo.common.context.ActorContext
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumStore
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.OfficerReaderSyncStore
import com.memo.forum.internal.OfficerReaders
import com.memo.forum.internal.SubmissionReaders
import com.memo.identity.UserAccount
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.org.OrgDirectory
import com.memo.org.internal.Assignment
import com.memo.org.internal.Assignments
import com.memo.org.internal.LegalEntities
import com.memo.org.internal.OrgCalendar
import com.memo.org.internal.OrgPosition
import com.memo.org.internal.OrgUnits
import com.memo.org.internal.Period
import com.memo.org.internal.Positions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Committee officers' memo access follows terms and position holders (ADR-0059; the E08 T10 finding): the daily run at
 * the start of the tenant's day retires a secretary whose term ended yesterday and admits one whose term starts today,
 * on the tenant's calendar (Asia/Dhaka, a day ahead of UTC from 18:00 UTC); a position seat's new holder reads the queued
 * memo at once and the old one stops; a holder change recorded for a later date applies at that day's run. Each change is
 * audited, and a second run that day changes nothing.
 */
class OfficerAccessIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var entities: LegalEntities

    @Autowired lateinit var units: OrgUnits

    @Autowired lateinit var positions: Positions

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var readers: SubmissionReaders

    @Autowired lateinit var forumStore: ForumStore

    @Autowired lateinit var memberStore: ForumMemberStore

    @Autowired lateinit var syncStore: OfficerReaderSyncStore

    @Autowired lateinit var events: ApplicationEventPublisher

    private lateinit var checker: UserAccount
    private lateinit var alice: Person
    private lateinit var reviewer: Person
    private lateinit var credit: UUID
    private lateinit var memoType: UUID
    private lateinit var today: LocalDate
    private val clock = MutableClock()

    @BeforeEach
    fun committee() {
        transactions.inTenant(tenant.id) { settings.put(OrgCalendar.TIME_ZONE, DHAKA.id) }
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        alice = person(clearance = 1)
        reviewer = person(clearance = 1)
        val type = asUser(admin) { forumTypes.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }
        credit = asUser(admin) { forums.create(ForumInput(KEY, "MCC", "Management Credit Committee", "Credit", type.type.id)) }.id
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"$KEY","wait":true,"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("to-committee", "To the credit committee", body) }
        asUser(checker) { seeding.approve("to-committee") }
        memoType =
            asUser(admin) { memoTypes.create(MemoTypeFields("Credit paper", null, false, true, "to-committee", RoutePolicy.FIXED)) }.id
        today = asUser(admin) { org.today() }
    }

    @Test
    fun `a secretary whose term ended yesterday stops reading at the day's run, and one whose term starts today reads`() {
        val leaving = person(clearance = 1)
        val arriving = person(clearance = 1)
        asUser(admin) { members.add(credit, MemberInput(userId = leaving.id, role = ForumRole.SECRETARY, termEndsOn = today)) }
        asUser(admin) { members.add(credit, MemberInput(userId = arriving.id, role = ForumRole.SECRETARY, startsOn = today.plusDays(1))) }
        val id = escalated()
        assertThat(get(id, leaving.session)).hasStatusOk()
        assertThat(get(id, arriving.session)).hasStatus(HttpStatus.NOT_FOUND)

        // The last second of the term's last day in Dhaka: still in office.
        clock.now = today.atTime(LocalTime.MAX).atZone(DHAKA).toInstant()
        daily()
        assertThat(get(id, leaving.session)).hasStatusOk()
        assertThat(get(id, arriving.session)).hasStatus(HttpStatus.NOT_FOUND)

        // Just after midnight in Dhaka, while it is still the same day in UTC: the term has ended, the next has started.
        clock.now =
            today
                .plusDays(1)
                .atTime(0, 0, 30)
                .atZone(DHAKA)
                .toInstant()
        assertThat(clock.now.atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(today)
        assertThat(daily()).containsExactly(credit)
        assertThat(get(id, leaving.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get(id, arriving.session)).hasStatusOk()
        Audit.assertAudited(tenant.id, "memo.reader.forum_retired", id)
        Audit.assertAudited(tenant.id, "memo.reader.forum_added", id)

        // Later runs the same day touch nothing.
        clock.advance(Duration.ofHours(1))
        assertThat(daily()).isEmpty()
        assertThat(get(id, arriving.session)).hasStatusOk()
    }

    @Test
    fun `a position seat's new holder reads the queued memo at once, and the old one stops`() {
        val first = person(clearance = 1)
        val second = person(clearance = 1)
        val (position, held) = position(first)
        asUser(admin) { members.add(credit, MemberInput(positionId = position.id, role = ForumRole.SECRETARY)) }
        val id = escalated()
        assertThat(get(id, first.session)).hasStatusOk()
        assertThat(get(id, second.session)).hasStatus(HttpStatus.NOT_FOUND)

        asUser(admin) {
            assignments.changeDates(held.id, held.version, Period(held.period.from, today))
            assignments.assign(position.id, second.id, Period(today, null))
        }
        assertThat(get(id, second.session)).hasStatusOk()
        assertThat(get(id, first.session)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.reader.forum_retired", id)
    }

    @Test
    fun `a holder change recorded for a later date applies at that day's run`() {
        val first = person(clearance = 1)
        val second = person(clearance = 1)
        val (position, held) = position(first)
        asUser(admin) { members.add(credit, MemberInput(positionId = position.id, role = ForumRole.CHAIR)) }
        val id = escalated()
        asUser(admin) {
            assignments.changeDates(held.id, held.version, Period(held.period.from, today.plusDays(1)))
            assignments.assign(position.id, second.id, Period(today.plusDays(1), null))
        }
        // Recorded today, it changes nothing today.
        assertThat(get(id, first.session)).hasStatusOk()
        assertThat(get(id, second.session)).hasStatus(HttpStatus.NOT_FOUND)
        clock.now = today.atTime(NOON).atZone(DHAKA).toInstant()
        daily()
        assertThat(get(id, first.session)).hasStatusOk()

        clock.now =
            today
                .plusDays(1)
                .atTime(0, 5)
                .atZone(DHAKA)
                .toInstant()
        assertThat(daily()).containsExactly(credit)
        assertThat(get(id, second.session)).hasStatusOk()
        assertThat(get(id, first.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `another tenant neither sees nor writes a tenant's sync day`() {
        clock.now = today.atTime(NOON).atZone(DHAKA).toInstant()
        daily()
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { c -> c.long("select count(*) from forum_reader_sync") }).isEqualTo(1)
        val other = registry.aTestTenant(transactions, settings, "officersother", mfaRequired = false)
        AppRoleJdbc.inTransaction(other.id) { c ->
            assertThat(c.long("select count(*) from forum_reader_sync where tenant_id = ?", tenant.id.value)).isZero()
            assertThat(c.update("update forum_reader_sync set synced_on = null, synced_at = null where tenant_id = ?", tenant.id.value))
                .isZero()
        }
        val written =
            runCatching {
                AppRoleJdbc.inTransaction(other.id) { c ->
                    c.update("insert into forum_reader_sync (tenant_id) values (?)", UUID.randomUUID())
                }
            }
        assertThat(written.isFailure).isTrue()
    }

    /** A memo of alice's, submitted and reviewed: now waiting for the credit committee. */
    private fun escalated(): String {
        val id = create(alice.session, """{"subject":"Loan","typeId":"$memoType"}""").json()["id"].asString()
        uploadDocument(id, alice.session)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        return id
    }

    /** A position held by [holder] since a month ago. */
    private fun position(holder: Person): Pair<OrgPosition, Assignment> {
        val from = today.minusDays(30)
        return asUser(admin) {
            val entity = entities.create("Bank ${UUID.randomUUID().toString().take(6)}", null, null, from)
            val unit = units.createUnit(entity.id, null, "SC${(100..999).random()}", "Secretariat", from)
            val p = positions.create(unit.id, null, "Company Secretary", false, from)
            p to assignments.assign(p.id, holder.id, Period(from, null))
        }
    }

    /** The day's run on the test clock, as the system: the committees it resynced. */
    private fun daily(): Set<UUID> =
        ActorContext.asSystem("forum-officer-readers") {
            transactions.inTenant(tenant.id) {
                OfficerReaders(readers, forumStore, memberStore, syncStore, org, events, clock).daily()
            }
        }

    private companion object {
        const val KEY = "credit-committee"
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        val NOON: LocalTime = LocalTime.NOON
    }
}
