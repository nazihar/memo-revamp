package com.memo.meeting

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
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
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.ical.InviteChecks
import com.memo.meeting.internal.ical.InviteKind
import com.memo.meeting.internal.ical.ParsedInvite
import com.memo.meeting.internal.invite.InvitationMessageKind
import com.memo.meeting.internal.invite.InvitationMessageRow
import com.memo.meeting.internal.invite.InvitationPanel
import com.memo.meeting.internal.invite.InvitationReconciler
import com.memo.meeting.internal.invite.InvitationRow
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.meeting.internal.invite.InvitationStore
import com.memo.meeting.internal.invite.InviteeRow
import com.memo.meeting.internal.invite.InviteeStore
import com.memo.notification.Mail
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.query
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * A committee with a secretary, a chair, two directors, a reader and an outsider, and helpers to schedule its meetings,
 * run the invitation reconciler as its job would (scheduling is off, so the tests decide when it runs), and read back
 * what was queued: the message log, each outbox row's calendar file (validated by [InviteChecks]) and parameters.
 */
abstract class InvitationTestSupport : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var meetingStore: MeetingStore

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var reconciler: InvitationReconciler

    @Autowired lateinit var scheduler: InvitationScheduler

    @Autowired lateinit var invitationStore: InvitationStore

    @Autowired lateinit var inviteeStore: InviteeStore

    @Autowired lateinit var panel: InvitationPanel

    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var json: JsonMapper

    lateinit var tenant: TestTenant
    lateinit var admin: UserAccount
    lateinit var secretary: UserAccount
    lateinit var chair: UserAccount
    lateinit var director: UserAccount
    lateinit var director2: UserAccount
    lateinit var reader: UserAccount
    lateinit var user: UserAccount
    lateinit var typeId: UUID
    lateinit var brc: UUID
    lateinit var directorSeat: ForumMemberRow
    val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @BeforeEach
    fun committee() {
        scheduler.jobs = false
        tenant = registry.aTestTenant(transactions, settings, label(), mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        chair = accounts.anActiveUser(transactions, tenant, aPassword())
        director = accounts.anActiveUser(transactions, tenant, aPassword())
        director2 = accounts.anActiveUser(transactions, tenant, aPassword())
        reader = accounts.anActiveUser(transactions, tenant, aPassword())
        user = accounts.anActiveUser(transactions, tenant, aPassword())
        typeId = asUser(admin) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", typeId)) }.id
        seat(secretary, ForumRole.SECRETARY)
        seat(chair, ForumRole.CHAIR)
        directorSeat = seat(director, ForumRole.MEMBER)
        seat(director2, ForumRole.MEMBER)
        asUser(admin) { access.grantCustom(reader.id.value, "Committee readers", setOf(Permission.FORUM_READ)) }
    }

    @AfterEach
    fun jobsBack() {
        scheduler.jobs = true
    }

    open fun label() = "invites"

    fun seat(
        account: UserAccount,
        role: ForumRole,
        termEndsOn: LocalDate? = null,
    ): ForumMemberRow = asUser(admin) { members.add(brc, MemberInput(userId = account.id.value, role = role, termEndsOn = termEndsOn)) }.row

    fun at(day: LocalDate) = day.atTime(LocalTime.of(10, 0))

    fun times(day: LocalDate) = TimesInput(at(day), at(day).plusHours(3), "Asia/Dhaka")

    fun place(
        location: String = "Board Room",
        link: String = "https://meet.example/abc",
        details: String? = "Meeting id 123",
        title: String? = null,
    ) = MeetingInput(MeetingKind.ORDINARY, MeetingPlace(title, MeetingMode.HYBRID, location, link, details), null)

    /** A meeting of the BRC on [day], scheduled by the secretary. */
    fun scheduled(day: LocalDate = today.plusDays(20)): MeetingRow =
        asUser(secretary) { lifecycle.create(brc, place(), times(day)).let { lifecycle.schedule(it.id, it.version) } }

    /** The current row of [meetingId]. */
    fun meeting(meetingId: UUID): MeetingRow = transactions.readInTenant(tenant.id) { checkNotNull(meetingStore.find(meetingId)) }

    /** Runs the reconciler as its job does, at [at] (default: after the debounce); the wait it answered. */
    fun reconcile(
        meetingId: UUID,
        at: Instant = Instant.now().plus(Duration.ofMinutes(3)),
    ): Duration? = ActorContext.asSystem("meeting-invitations") { TenantContext.call(tenant.id) { reconciler.run(meetingId, at) } }

    fun invitation(meetingId: UUID): InvitationRow? = transactions.readInTenant(tenant.id) { invitationStore.find(meetingId) }

    fun invitees(meetingId: UUID): Map<UUID, InviteeRow> =
        transactions.readInTenant(tenant.id) { inviteeStore.invitees(meetingId) }.associateBy { it.userId }

    fun messages(meetingId: UUID): List<InvitationMessageRow> = transactions.readInTenant(tenant.id) { inviteeStore.messages(meetingId) }

    /** The messages of [meetingId] after the first [skip], as "who:kind:sequence". */
    fun log(
        meetingId: UUID,
        skip: Int = 0,
    ): List<String> = messages(meetingId).drop(skip).map { "${name(it.userId)}:${it.kind}:${it.sequence}" }

    fun name(userId: UUID): String =
        mapOf(
            secretary to "secretary",
            chair to "chair",
            director to "director",
            director2 to "director2",
            reader to "reader",
            user to "user",
        ).entries
            .firstOrNull { it.key.id.value == userId }
            ?.value ?: userId.toString()

    /** The calendar file queued in outbox row [outboxId], validated as a file of [kind]. */
    fun file(
        outboxId: UUID,
        kind: InviteKind,
    ): Pair<String, ParsedInvite> {
        val encrypted =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select calendar_enc from notification_mail_outbox where id = ?", outboxId) { it.getBytes(1) }.single()
            }
        val bytes = keys.decrypt(checkNotNull(encrypted), Mail.calendarAad(tenant.id.toString(), outboxId.toString()))
        return String(bytes, Charsets.UTF_8) to InviteChecks.valid(bytes, kind)
    }

    /** The parameters of outbox row [outboxId] (decrypted). */
    fun params(outboxId: UUID): Map<String, String> {
        val encrypted =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.query("select params_enc, template from notification_mail_outbox where id = ?", outboxId) { it.getBytes(1) }.single()
            }
        val bytes = keys.decrypt(checkNotNull(encrypted), Mail.aad(tenant.id.toString(), outboxId.toString()))
        return json.readValue(bytes, Map::class.java).entries.associate { it.key.toString() to it.value.toString() }
    }

    fun template(outboxId: UUID): String =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select template from notification_mail_outbox where id = ?", outboxId) { it.getString(1) }.single()
        }

    /** The in-app items of [account] of [kind]. */
    fun items(
        account: UserAccount,
        kind: String,
    ): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select params::text from notification_item where user_id = ? and kind = ?", account.id.value, kind) { it.getString(1) }
        }

    fun email(account: UserAccount): String = account.email

    fun kindOf(message: InvitationMessageRow): InviteKind =
        when (message.kind) {
            InvitationMessageKind.INVITATION, InvitationMessageKind.UPDATE, InvitationMessageKind.RESEND -> InviteKind.REQUEST
            InvitationMessageKind.UNINVITE -> InviteKind.UNINVITE
            InvitationMessageKind.CANCEL -> InviteKind.CANCEL
        }

    fun <T> asUser(
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
