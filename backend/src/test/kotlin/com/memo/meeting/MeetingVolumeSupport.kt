package com.memo.meeting

import com.memo.authz.internal.ClassificationAdmin
import com.memo.common.authz.Clearances
import com.memo.common.cache.CacheInvalidation
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.Latency
import com.memo.testsupport.SqlRecorder
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The meeting volume's tenant and how its tests measure (design E03, E04 and E05 §8): [generate] writes [MeetingVolume] at
 * `MEETING_VOLUME` meetings (the design's 3,600) for 30 committees; [measure] prints and checks the plan of every
 * statement a call runs (no sequential scan of one of the [large] tables) and asserts its p95 under twice the budget, the
 * headroom for shared CI runners. The p95 values are printed for the epic log.
 */
abstract class MeetingVolumeSupport : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var classification: ClassificationAdmin

    @Autowired lateinit var clearances: Clearances

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var recorder: SqlRecorder

    @Autowired lateinit var invalidation: CacheInvalidation

    /** How many meetings to write: `MEETING_VOLUME`, or the variable a subclass gates on. */
    protected open val meetings: Int = System.getenv("MEETING_VOLUME")?.toIntOrNull() ?: DESIGN_MEETINGS
    protected lateinit var tenant: TestTenant
    protected lateinit var people: VolumePeople
    protected lateinit var volume: MeetingVolume
    protected lateinit var focalForum: UUID
    protected lateinit var levels: MeetingVolume.Levels

    /** The committees' ids and codes, the focal one first. */
    protected lateinit var committees: List<Pair<UUID, String>>

    /** The tables the volume makes large: a measured read must not scan one sequentially. */
    protected abstract val large: List<String>

    /** The class's name in what it prints. */
    private val label: String get() = javaClass.simpleName

    /**
     * Writes the volume in a new tenant [slug]; with [e05], E05's rows as well; with [mattersArising], that standing item;
     * with [histories], every committee's seats and attendance at design E08 §8's rates.
     */
    protected fun generate(
        slug: String,
        e05: Boolean,
        mattersArising: Boolean = false,
        histories: Boolean = false,
    ) {
        tenant = registry.aTestTenant(transactions, settings, slug)

        fun user(admin: Boolean = false) = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = admin).id.value
        people = VolumePeople(admin = user(admin = true), secretary = user(), director = user(), chair = user())
        levels = asUser(people.admin) { clearances.levels() }.sortedBy { it.rank }.let { MeetingVolume.Levels(it.first().id, it.last().id) }
        // The director reads the low level, not the high one: those items show as "Restricted item".
        asUser(people.admin, write = true) { classification.setClearance(people.director, levels.low) }
        val type =
            asUser(people.admin, write = true) {
                types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true)
            }.type.id
        committees =
            (1..FORUMS).map { f ->
                val forum = asUser(people.admin, write = true) { forums.create(ForumInput("c$f", "C$f", "Committee $f", "C$f", type)) }
                asUser(people.admin, write = true) {
                    members.add(forum.id, MemberInput(userId = people.secretary, role = ForumRole.SECRETARY))
                    if (f <= MeetingVolume.DIRECTOR_FORUMS) members.add(forum.id, MemberInput(userId = people.director))
                    if (f == 1) members.add(forum.id, MemberInput(userId = people.chair, role = ForumRole.CHAIR))
                }
                forum.id to "C$f"
            }
        focalForum = committees.first().first
        volume = MeetingVolume(tenant.id.value, committees, meetings / FORUMS, people, levels, e05, mattersArising, histories)
        val started = System.nanoTime()
        transactions.inTenant(tenant.id) {
            volume.generate(tenantDsl)
            // The volume seats forum 1's members (with the histories, every committee's) by SQL: their cached rosters are stale.
            (if (histories) committees.map { it.first } else listOf(focalForum)).forEach {
                invalidation.invalidate(ROSTER_CACHE, tenant.id, it.toString())
            }
        }
        volume.analyse()
        println("$label generated $meetings meetings in ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms")
        transactions.readInTenant(tenant.id) {
            val tables = MeetingVolume.TABLES + if (e05) MeetingVolume.E05_TABLES else emptyList()
            println("$label rows: ${tables.associateWith { tenantDsl.current().fetchValue("select count(*) from $it") }}")
        }
    }

    /**
     * The plan of every statement [call] runs as [user], printed and checked (no sequential scan of a large table); then
     * its p95 in a read transaction ([write]: a write transaction), the best of [Latency]'s rounds, asserted under 2x
     * [budget]. With [inside], the calls are timed without their transaction's begin and commit, in one round.
     */
    protected fun measure(
        name: String,
        budget: Long,
        user: UUID,
        write: Boolean = false,
        /** Timed inside the transaction, as for a call made within the caller's (a snapshot in E06's decision). */
        inside: Boolean = false,
        call: () -> Any,
    ) {
        repeat(WARM_UP) { asUser(user, write, call) }
        val statements = asUser(user, write) { recorder.record(call).second }
        plans(name, statements, user, write)
        if (inside) {
            report(name, (1..RUNS).map { asUser(user, write) { timed { call() } } }, budget)
        } else {
            budget(name, Latency.p95(0, RUNS) { asUser(user, write, call) }, budget)
        }
    }

    /**
     * Prints the plan of each read among [recorded] (`EXPLAIN ANALYZE` as [user], in a write transaction with [write]: a
     * locking read) and asserts that none scans one of the [large] tables sequentially.
     */
    protected fun plans(
        name: String,
        recorded: List<String>,
        user: UUID,
        write: Boolean = false,
    ) {
        val statements = recorded.filter { SELECT.containsMatchIn(it) }
        assertThat(statements).describedAs("$name: statements recorded").isNotEmpty
        println("$label $name: ${statements.size} statements")
        val scans =
            statements.mapIndexedNotNull { i, sql ->
                val plan = asUser(user, write) { explain(sql) }
                println("$label $name, statement ${i + 1}: ${sql.take(SQL_SHOWN)}\n$plan")
                large
                    .filter { table -> Regex("Seq Scan on $table\\b").containsMatchIn(plan) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { "statement ${i + 1} scans $it:\n$sql\n$plan" }
            }
        assertThat(scans).describedAs("$name: sequential scans of large tables").isEmpty()
    }

    protected fun report(
        name: String,
        times: List<Long>,
        budget: Long,
    ) = budget(name, times.sorted()[(times.size * P95 / 100) - 1], budget)

    /** Prints [p95] for the epic log and asserts it under twice [budget], the headroom for shared CI runners. */
    protected fun budget(
        name: String,
        p95: Long,
        budget: Long,
    ) {
        println("$label $meetings meetings, $name: p95 $p95 ms (budget $budget ms, CI limit ${budget * 2} ms)")
        assertThat(p95).describedAs(name).isLessThan(budget * 2)
    }

    protected fun timed(call: () -> Unit): Long {
        val start = System.nanoTime()
        call()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    private fun explain(sql: String): String =
        tenantDsl
            .current()
            .fetch("explain (analyze, buffers) $sql")
            .joinToString("\n") { it.get(0, String::class.java) }

    protected fun <T> asUser(
        user: UUID,
        write: Boolean = false,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(tenant.id) {
                if (write) transactions.inTenant(tenant.id, block) else transactions.readInTenant(tenant.id, block)
            }
        }

    companion object {
        const val FORUMS = 30
        const val DESIGN_MEETINGS = 3_600
        const val WARM_UP = 5
        const val RUNS = 20
        const val P95 = 95
        const val SQL_SHOWN = 400
        const val NANOS_PER_MILLI = 1_000_000L
        private const val ROSTER_CACHE = "forum.roster"
        val SELECT = Regex("^\\s*(select|with)\\b", RegexOption.IGNORE_CASE)
    }
}
