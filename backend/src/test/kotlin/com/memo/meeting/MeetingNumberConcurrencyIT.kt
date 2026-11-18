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
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingCounterStore
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.TimesInput
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Meeting numbers (test plan E03, T3; ADR-0060; AGENTS.md §4 gapless numbers): parallel first publications in one
 * committee give consecutive numbers, a rolled-back publication leaves no gap, a later revision keeps the number, and the
 * committee's code locks at the first number.
 */
class MeetingNumberConcurrencyIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var counters: MeetingCounterStore

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var notices: AgendaNoticeScheduler

    private lateinit var tenant: TestTenant
    private lateinit var secretary: UserAccount
    private lateinit var chair: UserAccount
    private lateinit var forum: UUID

    @BeforeEach
    fun setUp() {
        notices.jobs = false
        tenant = registry.aTestTenant(transactions, settings, "numbers", mfaRequired = false)
        val admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        chair = accounts.anActiveUser(transactions, tenant, aPassword())
        forum =
            asUser(admin) {
                val type = types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true).type.id
                forums.create(ForumInput("board-audit", "BAC", "Board Audit Committee", "Audit", type)).id
            }
        asUser(admin) { members.add(forum, MemberInput(userId = secretary.id.value, role = ForumRole.SECRETARY)) }
        asUser(admin) { members.add(forum, MemberInput(userId = chair.id.value, role = ForumRole.CHAIR)) }
    }

    @AfterEach
    fun jobsBack() {
        notices.jobs = true
    }

    @Test
    fun `parallel first publications take consecutive numbers, and a rolled-back one leaves no gap`() {
        val day = LocalDate.now(ZoneOffset.UTC).plusDays(DAYS_AHEAD)
        val year = day.year
        // A publication that rolls back after taking its number: the counter goes back with it.
        runCatching {
            asUser(secretary) {
                counters.next(forum, year)
                error("rolled back")
            }
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_counter where forum_id = ?", forum)).isZero()
        }

        val meetings = (0 until PARALLEL).map { ready(day) }
        val pool = Executors.newFixedThreadPool(PARALLEL)
        val go = CountDownLatch(1)
        try {
            val futures =
                meetings.map { (id, version) ->
                    pool.submit<Int> {
                        go.await()
                        asUser(secretary) { publisher.publish(id, version, null, true) }
                    }
                }
            go.countDown()
            assertThat(futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }).containsOnly(1)
        } finally {
            pool.shutdownNow()
        }

        val labels = meetings.map { (id, _) -> asUser(secretary) { reads.get(id) }.label }
        assertThat(labels).containsExactlyInAnyOrderElementsOf((1..PARALLEL).map { "BAC $it/$year" })
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select last from meeting_counter where forum_id = ? and year = ?", forum, year)).isEqualTo(PARALLEL.toLong())
            assertThat(c.long("select count(*) from forum where id = ? and code_locked_at is not null", forum)).isEqualTo(1)
        }

        // A later revision keeps the meeting's number; the next meeting takes the next one.
        val (first, _) = meetings.first()
        val label = asUser(secretary) { reads.get(first) }.label
        asUser(secretary) {
            items.add(first, views.draft(first).version, ItemInput(ItemKind.ITEM, title = "Internal audit plan", minutes = 20))
            publisher.publish(first, views.draft(first).version, "Internal audit plan added", false)
        }
        val next = ready(day)
        asUser(secretary) { publisher.publish(next.first, next.second, null, false) }
        assertThat(asUser(secretary) { reads.get(next.first) }.label).isEqualTo("BAC ${PARALLEL + 1}/$year")
        assertThat(asUser(secretary) { reads.get(first) }.label).isEqualTo(label)
        // Numbers run per calendar year of the meeting's date: a meeting next year starts again at 1.
        val nextYear = ready(day.plusYears(1))
        asUser(secretary) { publisher.publish(nextYear.first, nextYear.second, null, false) }
        assertThat(asUser(secretary) { reads.get(nextYear.first) }.label).isEqualTo("BAC 1/${year + 1}")
    }

    /** A scheduled meeting on [day] with its agenda started and agreed by the chair; its id and the agenda's version. */
    private fun ready(day: LocalDate): Pair<UUID, Long> {
        val at = day.atTime(LocalTime.of(10, 0))
        val id =
            asUser(secretary) {
                val created =
                    lifecycle.create(
                        forum,
                        MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                        TimesInput(at, at.plusHours(2), "Asia/Dhaka"),
                    )
                starts.start(created.id, AgendaOrigin.TEMPLATE, null)
                lifecycle.schedule(created.id, created.version).id
            }
        asUser(chair) { chairing.agree(id, views.draft(id).version, false, null) }
        return id to asUser(secretary) { views.draft(id) }.version
    }

    private fun <T> asUser(
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }

    private companion object {
        const val PARALLEL = 4
        const val DAYS_AHEAD = 40L
        const val WAIT_SECONDS = 60L
    }
}
