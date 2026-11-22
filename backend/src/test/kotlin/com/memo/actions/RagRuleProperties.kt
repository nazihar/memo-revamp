package com.memo.actions

import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.Confidence
import com.memo.actions.internal.RagColour
import com.memo.actions.internal.RagColumns
import com.memo.actions.internal.RagFacts
import com.memo.actions.internal.RagRule
import com.memo.actions.internal.RagWord
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTestTenant
import com.memo.workflow.calendar.WorkingDayRules
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/**
 * Red, amber and green read two ways agree (test plan E07 T4 `RagRuleProperties`; design E07 §1.5, ADR-0067): for random
 * calendars (a Saturday–Sunday or a Friday–Saturday weekend, random holidays), due dates, amber and stale windows counted
 * on them, last updates, confidences, overrides with and without an end, statuses and today, the Kotlin rule
 * ([RagRule.word]) and the SQL expression ([RagRule.field], run by PostgreSQL over the same columns) give the same word,
 * hence the same colour. A fixed seed per batch keeps a failure reproducible.
 */
class RagRuleProperties : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    /** One random case: what the rule reads, and the day it is read. */
    private data class Case(
        val facts: RagFacts,
        val today: LocalDate,
    )

    private fun cases(
        seed: Int,
        count: Int,
    ): List<Case> {
        val random = Random(seed)
        val start = LocalDate.of(2026, 1, 1)

        fun day(span: Int) = start.plusDays(random.nextInt(span).toLong())
        return List(count) {
            val weekend =
                if (random.nextBoolean()) {
                    setOf(
                        DayOfWeek.SATURDAY,
                        DayOfWeek.SUNDAY,
                    )
                } else {
                    setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
                }
            val holidays = List(random.nextInt(30)) { day(DAYS) }.toSet()
            val working = { d: LocalDate -> d.dayOfWeek !in weekend && d !in holidays }
            val due = day(DAYS).takeIf { random.nextInt(10) > 0 }
            val amberFrom = due?.let { WorkingDayRules.before(it, 1 + random.nextInt(60), working) }
            val staleDays = listOf(0, 5, 20, 120).random(random)
            val lastUpdate = day(DAYS)
            val staleFrom = staleDays.takeIf { it > 0 }?.let { WorkingDayRules.after(lastUpdate, it, working) }
            val override = (RagRule.OVERRIDES.toList() + listOf(null, null, null)).random(random)
            val until = override?.let { day(DAYS).takeIf { random.nextBoolean() } }
            val facts =
                RagFacts(
                    status = ActionStatus.entries.random(random),
                    dueDate = due,
                    amberFrom = amberFrom,
                    staleFrom = staleFrom,
                    confidence = (Confidence.entries + listOf(null, null)).random(random),
                    override = override,
                    overrideUntil = until,
                )
            // Today often falls on one of the boundaries, where an off-by-one would show.
            val today =
                listOfNotNull(
                    due,
                    due?.plusDays(1),
                    amberFrom,
                    amberFrom?.minusDays(1),
                    staleFrom,
                    staleFrom?.minusDays(1),
                    until,
                    until?.plusDays(1),
                ).plus(day(DAYS))
                    .random(random)
            Case(facts, today)
        }
    }

    /** The SQL reading of [cases], in their order: the expression over a derived table of their columns. */
    private fun sql(cases: List<Case>): List<RagWord> = query(cases, null).map { it.second }

    /** The cases the SQL filter for [colour] keeps. */
    private fun shown(
        cases: List<Case>,
        colour: RagColour,
    ): List<Int> = query(cases, colour).map { it.first }

    /** The cases (their index and SQL word), all or those [RagRule.shows] keeps for [colour]. */
    private fun query(
        cases: List<Case>,
        colour: RagColour?,
    ): List<Pair<Int, RagWord>> {
        val rows =
            cases.mapIndexed { i, case ->
                val f = case.facts
                DSL.row(
                    DSL.inline(i),
                    text(f.status.name),
                    date(f.dueDate),
                    date(f.amberFrom),
                    date(f.staleFrom),
                    text(f.confidence?.name),
                    text(f.override?.name),
                    date(f.overrideUntil),
                    date(case.today),
                )
            }
        val t =
            DSL
                .values(*rows.toTypedArray())
                .`as`("t", "n", "status", "due_date", "amber_from", "stale_from", "confidence", "rag_override", "until", "today")
        val columns =
            RagColumns(
                status = t.field("status", String::class.java)!!,
                dueDate = t.field("due_date", LocalDate::class.java)!!,
                amberFrom = t.field("amber_from", LocalDate::class.java)!!,
                staleFrom = t.field("stale_from", LocalDate::class.java)!!,
                confidence = t.field("confidence", String::class.java)!!,
                override = t.field("rag_override", String::class.java)!!,
                overrideUntil = t.field("until", LocalDate::class.java)!!,
            )
        val today: Field<LocalDate?> = t.field("today", LocalDate::class.java)!!
        val n = t.field("n", Int::class.java)!!
        return transactions.readInTenant(tenant().id) {
            tenantDsl
                .current()
                .select(n, RagRule.field(today, columns))
                .from(t)
                .where(colour?.let { RagRule.shows(it, today, columns) } ?: DSL.noCondition())
                .orderBy(n)
                .fetch { it.value1()!! to RagWord.valueOf(it.value2()) }
        }
    }

    @Test
    fun `the Kotlin rule and the SQL expression give the same words and colours`() {
        repeat(BATCHES) { batch ->
            val cases = cases(SEED + batch, PER_BATCH)
            val inSql = sql(cases)
            cases.forEachIndexed { i, case ->
                val inKotlin = RagRule.word(case.facts, case.today)
                assertThat(inSql[i]).describedAs("batch ${SEED + batch}, case $i: $case").isEqualTo(inKotlin)
                assertThat(inSql[i].colour).isEqualTo(inKotlin.colour)
            }
        }
    }

    @Test
    fun `the colour filter is the same rule`() {
        val cases = cases(SEED, PER_BATCH)
        RagColour.entries.forEach { colour ->
            val expected = cases.indices.filter { RagRule.word(cases[it].facts, cases[it].today).colour == colour }
            assertThat(shown(cases, colour)).describedAs(colour.name).isEqualTo(expected)
        }
    }

    private var created: com.memo.testsupport.TestTenant? = null

    private fun tenant() = created ?: registry.aTestTenant(transactions, settings, "rag").also { created = it }

    private fun text(value: String?): Field<String?> = DSL.cast(DSL.inline(value), SQLDataType.VARCHAR)

    private fun date(value: LocalDate?): Field<LocalDate?> = DSL.cast(DSL.inline(value), SQLDataType.LOCALDATE)

    private companion object {
        const val SEED = 20_261_121
        const val BATCHES = 8
        const val PER_BATCH = 500
        const val DAYS = 400
    }
}
