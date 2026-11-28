package com.memo.analytics

import com.memo.actions.ActionsVolume
import com.memo.actions.internal.ActionClosure
import com.memo.actions.internal.ActionFields
import com.memo.actions.internal.ActionIssuing
import com.memo.actions.internal.ActionRaising
import com.memo.actions.internal.ActionSource
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ActionsDaily
import com.memo.actions.internal.OwnerInput
import com.memo.actions.internal.OwnerKind
import com.memo.actions.internal.RaiseInput
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumRole
import com.memo.forum.MinutesApproval
import com.memo.forum.MinutesPolicy
import com.memo.forum.internal.MemberInput
import com.memo.identity.MfaSeeding
import com.memo.identity.UserId
import com.memo.identity.auth.AuthSettings
import com.memo.meeting.MeetingStatus
import com.memo.meeting.MeetingVolume
import com.memo.meeting.MeetingVolumeIT
import com.memo.meeting.MeetingVolumeSupport
import com.memo.meeting.Meetings
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.minutes.MinutesVolume
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.org.internal.OrgCalendar
import com.memo.search.internal.decision.DecisionIndexing
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkingDays
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * The committee volume of design E08 §8 (test plan E08 T5), shared by [com.memo.search.RegisterVolumeIT],
 * [AnalyticsVolumeIT] and the load host [CommitteeLoadHostIT]: at `COMMITTEE_VOLUME` meetings (the design's 3,600: 30
 * committees × 10 years × 12) in a tenant of its own, in stages that commit and are analysed before the next:
 *  1. [MeetingVolume] with E05's rows, "Matters arising" on every agenda and the **attendance histories** (every
 *     committee seated, apologies, absences, alternates standing in, late arrivals and early departures at design §8's
 *     rates: members attend about 90 %);
 *  2. [ActionsVolume] over its held meetings (about 29,000 actions, 3,400 open, with running workflows);
 *  3. [MinutesVolume] (about 55,000 decisions, 30,000 resolution numbers, the minutes; at 7,200 meetings about 108,000);
 *  4. the register's **backfill** through `search-decisions-backfill`'s own work ([DecisionIndexing.backfill], chunks of
 *     500, each in its own transaction, as the job runs them), timed against design §8's ten minutes;
 *  5. the five reader profiles of design §8 ([Profiles]), [PER_PROFILE] people each: a secretary of three committees, a
 *     member of two, an auditor with `forum.read`, a member of two recused from one decided item in twenty, and a
 *     `forums.manage` holder. They are the action volume's people (each owns a few actions), with a password and, when
 *     given, a TOTP secret.
 *
 * The tenant's zone is the meetings' (Asia/Dhaka), so periods and meeting dates agree (#247). Generated once per JVM: the
 * classes that share it reuse the first one's tenant.
 */
abstract class CommitteeVolumeSupport : MeetingVolumeSupport() {
    override val meetings: Int = System.getenv(VOLUME_VARIABLE)?.toIntOrNull() ?: DESIGN_MEETINGS

    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var indexing: DecisionIndexing

    @Autowired lateinit var daily: ActionsDaily

    @Autowired lateinit var raising: ActionRaising

    @Autowired lateinit var issuing: ActionIssuing

    @Autowired lateinit var closure: ActionClosure

    @Autowired lateinit var actionStore: ActionStore

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var workingDays: WorkingDays

    @Autowired lateinit var meetingRefs: Meetings

    @Autowired lateinit var grants: AccessAdministration

    @Autowired lateinit var mfa: MfaSeeding

    @Autowired lateinit var invitationJobs: InvitationScheduler

    @Autowired lateinit var agendaJobs: AgendaNoticeScheduler

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var releaseJobs: MinutesReleaseScheduler

    /** The reader profiles of design E08 §8, the first person of each the one the volume tests measure. */
    data class Profiles(
        val secretaries: List<UUID>,
        val members: List<UUID>,
        val auditors: List<UUID>,
        val recused: List<UUID>,
        val managers: List<UUID>,
    ) {
        /** Profile name → its people. */
        fun byName(): Map<String, List<UUID>> =
            linkedMapOf(
                SECRETARY to secretaries,
                MEMBER to members,
                AUDITOR to auditors,
                RECUSED to recused,
                MANAGER to managers,
            )
    }

    /** What the generator made, kept for the next class in the same JVM. */
    private data class Generated(
        val meetings: Int,
        val tenant: com.memo.testsupport.TestTenant,
        val people: com.memo.meeting.VolumePeople,
        val volume: MeetingVolume,
        val committees: List<Pair<UUID, String>>,
        val levels: MeetingVolume.Levels,
        val profiles: Profiles,
        val persons: Int,
        val backfillMillis: Long,
        val decisions: Long,
    )

    protected lateinit var profiles: Profiles

    /** How long the register's backfill took, and how many decisions it indexed. */
    protected var backfillMillis: Long = 0
    protected var decisions: Long = 0

    /** The action volume's people (each an identity user). */
    protected var persons: Int = 0

    override val large: List<String> =
        MeetingVolumeIT.LARGE +
            (MeetingVolume.E05_TABLES - listOf("forum_member", "notification_mail_outbox", "meeting_item_progress")) +
            (MinutesVolume.TABLES - listOf("minutes_resolution_counter", "minutes_comment_request", "minutes_reminder")) +
            ACTION_TABLES + "search_decision"

    /**
     * Generates the committee volume in a tenant [slug] (or takes the one this JVM generated already), with [PER_PROFILE]
     * people in each reader profile (the load's ten; the volume tests measure the first), signing in with
     * `MEMO_LOAD_PASSWORD` and the TOTP secret `MEMO_LOAD_TOTP_SECRET` when set (the load host's), else a password of the
     * run's own. Whichever class of a run comes first generates it, so the others can share it.
     */
    protected fun generateCommittees(slug: String) {
        val perProfile = PER_PROFILE
        val password = System.getenv("MEMO_LOAD_PASSWORD") ?: aPassword()
        val totpSecret = System.getenv("MEMO_LOAD_TOTP_SECRET")
        // Other test contexts share the scheduler table: nothing here runs the jobs, which would act on the volume.
        daily.jobs = false
        invitationJobs.jobs = false
        agendaJobs.jobs = false
        minutesJobs.jobs = false
        releaseJobs.jobs = false
        cached?.takeIf { it.meetings == meetings }?.let {
            restore(it)
            return
        }
        generate(slug, e05 = true, mattersArising = true, histories = true)
        transactions.inTenant(tenant.id) {
            settings.put(OrgCalendar.TIME_ZONE, ZONE)
            // Holders of privileged permissions (forums.manage) need MFA; the others sign in with a password, as at a bank
            // that relaxed it for directors.
            settings.put(AuthSettings.MFA_REQUIRED, AuthSettings.MFA_ADMINS_ONLY)
        }
        val started = System.nanoTime()
        actionsStage()
        println("$label generated the actions in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
        val minutesStarted = System.nanoTime()
        val stages =
            MinutesVolume(
                tenant.id.value,
                people,
                volume.focal,
                volume.held,
                json.writeValueAsString(MinutesPolicy()),
                json.writeValueAsString(MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION)),
            ).generate()
        // The first committee's officers' seats were moved by SQL: its cached roster is stale.
        invalidation.invalidate(ROSTER, tenant.id, focalForum.toString())
        println("$label generated the minutes in ${(System.nanoTime() - minutesStarted) / NANOS_PER_MILLI} ms: $stages")
        profiles = profilesStage(perProfile, password, totpSecret)
        backfill()
        cached =
            Generated(
                meetings,
                tenant,
                people,
                volume,
                committees,
                levels,
                profiles,
                persons,
                backfillMillis,
                decisions,
            )
    }

    /** The action volume as `ActionsVolumeIT` writes it: rows by SQL, then each open action's workflow from two issued. */
    private fun actionsStage() {
        val zone = asUser(people.admin) { org.zone() }
        val today = asUser(people.admin) { org.today() }
        val runDay = checkNotNull(asUser(people.admin) { workingDays.before(today, 1) })
        persons = (ActionsVolume.PERSONS.toLong() * meetings / DESIGN_MEETINGS).toInt().coerceAtLeast(MIN_PERSONS)
        val actions =
            ActionsVolume(
                tenant = tenant.id.value,
                forums = committees,
                perForum = meetings / FORUMS,
                people = people,
                levels = levels,
                position = directorPosition(today),
                zone = zone.id,
                runDay = runDay,
                focal = volume.focal,
                persons = persons,
            )
        actions.generate()
        val held =
            asUser(people.admin) { meetingRefs.ofForum(focalForum) }
                .filter { it.status == MeetingStatus.HELD }
                .maxByOrNull { it.startsAt }
                .let { checkNotNull(it).id }
        actions.workflows(template(held, today, done = false), template(held, today, done = true))
        actions.settle()
        println("$label action rows: ${actions.counts()}")
    }

    private fun template(
        meeting: UUID,
        today: LocalDate,
        done: Boolean,
    ): ActionsVolume.Template {
        val fields =
            ActionFields(
                "Report the revised collateral valuation",
                "Details",
                null,
                OwnerInput(OwnerKind.PERSON, userId = people.director),
                emptyList(),
                today.plusDays(DUE_DAYS),
            )
        val input = RaiseInput(ActionSource.ITEM, volume.generated("mvi$meeting-4-1"), null, null, fields)
        val id = asUser(people.secretary, write = true) { raising.raise(meeting, input) }
        asUser(people.secretary, write = true) { issuing.issue(id, version(id)) }
        val issued = asUser(people.admin) { checkNotNull(actionStore.find(id)) }
        if (done) asUser(people.director, write = true) { closure.done(id, issued.version, "Done as asked.", emptyList()) }
        val row = asUser(people.admin) { checkNotNull(actionStore.find(id)) }
        return ActionsVolume.Template(
            actionId = id,
            instanceId = checkNotNull(row.workflowInstanceId),
            holder = people.director,
            dueDate = checkNotNull(row.dueDate),
            stepName = row.stepName,
            ownerStep = issued.ownerStep,
            holderNow = row.holderUserId?.takeIf { it != people.director },
        )
    }

    private fun version(id: UUID) = asUser(people.admin) { checkNotNull(actionStore.find(id)).version }

    /** "Head of Credit Risk", held by the director (a position owner's holder in "My actions"). */
    private fun directorPosition(today: LocalDate): UUID {
        val position = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val start = today.minusYears(1)
        asUser(people.admin, write = true) {
            orgSeeding.legalEntity(entity, "Bank", start)
            orgSeeding.unit(unit, entity, null, "CR", "Credit Risk", start)
            orgSeeding.position(position, unit, "Head of Credit Risk", head = true, start)
            orgSeeding.assignment(position, people.director, start)
        }
        return position
    }

    /**
     * The reader profiles, from the action volume's people (`avp1@volume.example` onwards, [perProfile] a profile, in the
     * order of [Profiles.byName]): seated or granted through the services, a password and the TOTP secret set; the
     * recused members' declarations written by SQL on one decided item in twenty of their two committees.
     */
    private fun profilesStage(
        perProfile: Int,
        password: String,
        totpSecret: String?,
    ): Profiles {
        require(PROFILES * perProfile <= persons) { "the volume has $persons people for ${PROFILES * perProfile} profiles" }
        val person = { n: Int -> volume.generated("avp$n") }
        val group = { g: Int -> (1..perProfile).map { person(g * perProfile + it) } }
        val result = Profiles(group(0), group(1), group(2), group(3), group(4))
        val ids = committees.map { it.first }
        // The members' and then the recused members' two committees each, in turn.
        val seated =
            (result.members + result.recused).mapIndexed { i, user ->
                user to (0 until MEMBER_COMMITTEES).map { ids[(i * MEMBER_COMMITTEES + it) % ids.size] }
            }
        asUser(people.admin, write = true) {
            result.secretaries.forEachIndexed { i, user ->
                (0 until SECRETARY_COMMITTEES).forEach { c ->
                    members.add(ids[(i * SECRETARY_COMMITTEES + c) % ids.size], MemberInput(userId = user, role = ForumRole.SECRETARY))
                }
            }
            seated.forEach { (user, forums) -> forums.forEach { members.add(it, MemberInput(userId = user)) } }
            result.auditors.forEach { grants.grantCustom(it, "Committee readers", setOf(Permission.FORUM_READ)) }
            result.managers.forEach { grants.grantCustom(it, "Committee managers", setOf(Permission.FORUMS_MANAGE)) }
            result.byName().values.flatten().forEach { user ->
                accounts.setPassword(UserId(user), password)
                if (totpSecret != null) mfa.ensureTotp(UserId(user), totpSecret)
            }
        }
        ids.forEach { invalidation.invalidate(ROSTER, tenant.id, it.toString()) }
        declare(seated.filter { it.first in result.recused })
        return result
    }

    /** Each of [recused] declared an interest (and was recused) in one decided item in twenty of their committees. */
    private fun declare(recused: List<Pair<UUID, List<UUID>>>) =
        owner { c ->
            recused.forEach { (user, forums) ->
                val inForums = forums.joinToString { "'$it'::uuid" }
                c.createStatement().use {
                    it.execute(
                        """
                        insert into meeting_declaration (id, tenant_id, meeting_id, item_id, forum_id, user_id, nature, intent, source,
                                                         recorded_by, how, declared_at, at_meeting, revision, state)
                        select md5('cvd' || d.item_id || '$user')::uuid, d.tenant_id, d.meeting_id, d.item_id, d.forum_id, '$user'::uuid,
                               'Interest in the counterparty', 'LEAVE', 'SELF', '$user'::uuid, 'IN_APP',
                               m.starts_at - interval '10 days', false, 1, 'RECUSED'
                        from minutes_decision d join meeting m on m.tenant_id = d.tenant_id and m.id = d.meeting_id
                        where d.forum_id in ($inForums) and abs(hashtext(d.item_id::text)) % $RECUSED_EVERY = 0
                        """,
                    )
                    it.execute(
                        """
                        insert into meeting_declaration_event (id, tenant_id, declaration_id, kind, state_after, actor_id, how, note, at)
                        select md5('cvde' || id)::uuid, tenant_id, id, 'DECLARED', 'RECUSED', user_id, 'IN_APP', null, declared_at
                        from meeting_declaration where user_id = '$user'::uuid and id = md5('cvd' || item_id || '$user')::uuid
                        """,
                    )
                }
            }
            c.commit()
            c.createStatement().use { it.execute("analyze meeting_declaration, meeting_declaration_event") }
        }

    /**
     * The register's backfill as `search-decisions-backfill` runs it (chunks of [DecisionIndexing.CHUNK], each in its own
     * transaction, as the system), until nothing is missing; timed, then the table analysed.
     */
    private fun backfill() {
        val started = System.nanoTime()
        var chunks = 0
        ActorContext.asSystem("search-decisions-backfill") {
            TenantContext.call(tenant.id) {
                do {
                    val n = transactions.inTenant(tenant.id) { indexing.backfill() }
                    chunks++
                } while (n == DecisionIndexing.CHUNK)
                transactions.inTenant(tenant.id) { assertThat(indexing.unindexed()).describedAs("decisions left unindexed").isZero() }
            }
        }
        backfillMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
        // Planned and timed on a vacuumed table, as autovacuum leaves it in production: the GIN index's pending list merged
        // (`fastupdate`) and the visibility map set. With only ANALYZE, the backfill's pending entries made the text search
        // over every committee plan as a sequential scan on a CI runner (load run 46156574343), as `SearchVolume` found for
        // the memo search.
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute("set statement_timeout = '30min'") }
            c.createStatement().use { it.execute("vacuum (analyze) search_decision") }
        }
        owner { c ->
            decisions =
                c.createStatement().use { st ->
                    st.executeQuery("select count(*) from search_decision").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
        }
        println("$label backfilled $decisions decisions in $chunks chunks in $backfillMillis ms (budget ${BACKFILL_BUDGET_MS} ms)")
    }

    private fun restore(g: Generated) {
        tenant = g.tenant
        people = g.people
        volume = g.volume
        committees = g.committees
        focalForum = g.committees.first().first
        levels = g.levels
        profiles = g.profiles
        persons = g.persons
        backfillMillis = g.backfillMillis
        decisions = g.decisions
        println("$label reuses the committee volume of tenant ${tenant.slug} ($decisions decisions)")
    }

    /** The class's name in what it prints. */
    protected val label: String get() = javaClass.simpleName

    /** A committed transaction as the schema owner, bound to the tenant. */
    protected fun <T> owner(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("set local statement_timeout = '30min'") }
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, tenant.id.value.toString())
                it.execute()
            }
            val result = block(c)
            c.commit()
            result
        }

    companion object {
        /** The meetings to generate; the tests are skipped without it (weekly `load`). */
        const val VOLUME_VARIABLE = "COMMITTEE_VOLUME"
        const val ZONE = "Asia/Dhaka"
        const val SECRETARY = "secretary of three"
        const val MEMBER = "member of two"
        const val AUDITOR = "auditor (forum.read)"
        const val RECUSED = "recused member of two"
        const val MANAGER = "forums.manage"
        const val PROFILES = 5

        /** People in each reader profile: the load simulation's 50 people are ten of each. */
        const val PER_PROFILE = 10
        const val SECRETARY_COMMITTEES = 3
        const val MEMBER_COMMITTEES = 2
        const val RECUSED_EVERY = 20
        const val MIN_PERSONS = 200
        const val DUE_DAYS = 40L
        const val ROSTER = "forum.roster"

        /** Design E08 §8: the backfill of a tenant of 54,000 decisions. */
        const val BACKFILL_BUDGET_MS = 600_000L

        /** The action tables the volume makes large (as `ActionsVolumeIT`'s). */
        val ACTION_TABLES =
            listOf(
                "actions_item",
                "actions_entry",
                "actions_contributor",
                "actions_due_request",
                "actions_digest",
                "actions_report",
                "actions_report_row",
            )

        @Volatile private var cached: Generated? = null
    }
}
