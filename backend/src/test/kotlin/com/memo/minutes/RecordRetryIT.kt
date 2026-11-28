package com.memo.minutes

import com.memo.forum.ForumRole
import com.memo.forum.MinutesApproval
import com.memo.minutes.internal.CopyState
import com.memo.minutes.internal.MinutesCopyStore
import com.memo.minutes.internal.MinutesJobTasks
import com.memo.minutes.internal.MinutesRecordStore
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.RecordRetries
import com.memo.minutes.internal.RecordState
import com.memo.testsupport.Audit
import com.memo.testsupport.CachingDocumentServer
import com.memo.testsupport.FakeRenditionServices
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A record of approved minutes, or a reader's approved copy, that failed on its job's last try is asked for again (found
 * by the E08 T10 runbook check: nothing ever asked again, so readers got 409 `RECORD_PENDING` or 202 for ever). The
 * document server is the [CachingDocumentServer] stand-in, down for longer than all the tries; then the minutes' sweep
 * asks again the next day with backoff, and a secretary not recused can "Make the record again". Meanwhile readers are
 * told the truth (409 `RECORD_FAILED`) and the secretaries are told once. The jobs are run by hand.
 */
class RecordRetryIT : MinutesFixture() {
    @Autowired lateinit var records: MinutesRecords

    @Autowired lateinit var recordStore: MinutesRecordStore

    @Autowired lateinit var copyStore: MinutesCopyStore

    private val server = CachingDocumentServer(FakeRenditionServices::converted)

    @BeforeEach
    fun route() {
        FakeRenditionServices.documentServer =
            FakeRenditionServices.Route(setOf(FakeRenditionServices.MINUTES_COPY, FakeRenditionServices.MINUTES_RECORD), server.converter())
    }

    @AfterEach
    fun unroute() {
        FakeRenditionServices.documentServer = null
        server.close()
    }

    @Test
    fun `a record that failed through a long outage is asked for again by the sweep, the next day and then with backoff`() {
        val (m, _) = approvedMinutes()
        val failedAt = Instant.now()
        failRecord(m)
        val failed = record(m)
        assertThat(failed.state to failed.error).isEqualTo(RecordState.FAILED to "CONVERTER_CONVERTER_M3")
        assertThat(failed.failedAt).isNotNull()

        // The truth for readers; the secretary is told once, and may make it again.
        listOf("record", "document").forEach { path ->
            val answer = read("/api/v1/meetings/$m/minutes/$path", colleague.session)
            assertThat(answer).hasStatus(HttpStatus.CONFLICT)
            assertThat(answer.response.contentAsString).contains("RECORD_FAILED").doesNotContain("RECORD_PENDING")
        }
        assertThat(read("/api/v1/meetings/$m/minutes/records", colleague.session).json()[0]["failed"].asBoolean()).isTrue()
        assertThat(page(m, secretary)["mayRetryRecord"].asBoolean()).isTrue()
        assertThat(page(m, colleague)["mayRetryRecord"].asBoolean()).isFalse()
        assertThat(kinds(secretary).count { it == "MINUTES_RECORD_FAILED" }).isEqualTo(1)
        Audit.assertAudited(tenant.id, "minutes.reminded.record_failed", m.toString())

        // The same day the sweep waits; the next day it asks again, once, with a fresh count of tries.
        assertThat(sweep(failedAt.plus(Duration.ofMinutes(1)))).isZero()
        assertThat(record(m).state).isEqualTo(RecordState.FAILED)
        assertThat(sweep(failedAt.plus(Duration.ofDays(1)))).isEqualTo(1)
        val asked = record(m)
        assertThat(asked.state to asked.retries).isEqualTo(RecordState.PENDING to 1)
        assertThat(asked.error).isNull()
        val retried = Audit.assertAudited(tenant.id, "minutes.record.retried", m.toString())
        assertThat(retried.canonical).contains("\"trigger\":\"SWEEP\"", "\"record\":true")
        assertThat(sweep(failedAt.plus(Duration.ofDays(1)))).describedAs("already pending").isZero()
        assertThat(read("/api/v1/meetings/$m/minutes/record", colleague.session).response.contentAsString).contains("RECORD_PENDING")

        // Still down: it fails again (no second notice), and the sweep then waits two days.
        failRecord(m)
        val again = record(m)
        assertThat(again.state to again.retries).isEqualTo(RecordState.FAILED to 1)
        assertThat(kinds(secretary).count { it == "MINUTES_RECORD_FAILED" }).isEqualTo(1)
        val second = checkNotNull(again.failedAt)
        assertThat(sweep(second.plus(Duration.ofDays(1)))).isZero()
        assertThat(sweep(second.plus(Duration.ofDays(2)))).isEqualTo(1)

        // The document server is back: the job's next try makes it, and readers read it.
        server.down = false
        asSystem { records.build(m, 1, last = false) }
        assertThat(record(m).state).isEqualTo(RecordState.READY)
        assertThat(read("/api/v1/meetings/$m/minutes/record", colleague.session)).hasStatusOk()
        assertThat(page(m, secretary)["mayRetryRecord"].asBoolean()).isFalse()
        assertThat(sweep(second.plus(Duration.ofDays(40)))).describedAs("nothing failed").isZero()
    }

    @Test
    fun `a reader's approved copy that failed is said so, and the sweep asks for it again`() {
        val (m, _) = approvedMinutes()
        asSystem { records.build(m, 1, last = true) }
        assertThat(record(m).state).isEqualTo(RecordState.READY)
        server.down = true
        repeat(MinutesJobTasks.MAX_RETRIES - 1) {
            assertThat(runCatching { asSystem { minutesCopies.build(m, last = false) } }.isFailure).isTrue()
        }
        buildCopies(m)
        val copy = transactions.readInTenant(tenant.id) { copyStore.ofRecord(m, 1) }.single()
        assertThat(copy.state).isEqualTo(CopyState.FAILED)

        // The recused director is told the truth rather than "being made" for ever; the others read the record.
        val theirs = read("/api/v1/meetings/$m/minutes/record", director.session)
        assertThat(theirs).hasStatus(HttpStatus.CONFLICT)
        assertThat(theirs.response.contentAsString).contains("RECORD_FAILED")
        assertThat(read("/api/v1/meetings/$m/minutes/records", director.session).json()[0]["failed"].asBoolean()).isTrue()
        assertThat(read("/api/v1/meetings/$m/minutes/record", colleague.session)).hasStatusOk()
        assertThat(kinds(secretary).count { it == "MINUTES_RECORD_FAILED" }).isEqualTo(1)
        assertThat(page(m, secretary)["mayRetryRecord"].asBoolean()).isTrue()

        server.down = false
        val seq = row(m).copiesSeq
        assertThat(sweep(checkNotNull(copy.failedAt).plus(Duration.ofDays(1)))).isEqualTo(1)
        val asked = transactions.readInTenant(tenant.id) { copyStore.ofRecord(m, 1) }.single()
        assertThat(asked.state to asked.retries).isEqualTo(CopyState.PENDING to 1)
        assertThat(row(m).copiesSeq).describedAs("a new copies job").isEqualTo(seq + 1)
        assertThat(read("/api/v1/meetings/$m/minutes/record", director.session)).hasStatus(HttpStatus.ACCEPTED)
        buildCopies(m)
        assertThat(transactions.readInTenant(tenant.id) { copyStore.ofRecord(m, 1) }.single().state).isEqualTo(CopyState.DONE)
        assertThat(read("/api/v1/meetings/$m/minutes/record", director.session)).hasStatusOk()
    }

    @Test
    fun `a secretary not recused makes the record again, everyone else is refused, and nothing failed is 409`() {
        val (m, recusedSecretary) = approvedMinutes()
        assertThat(refusalOf(retry(m, secretary), HttpStatus.CONFLICT)).describedAs("pending, not failed").isEqualTo("RECORD_NOT_FAILED")
        failRecord(m)
        assertThat(kinds(recusedSecretary)).describedAs("a recused secretary cannot act").doesNotContain("MINUTES_RECORD_FAILED")

        assertThat(refusalOf(retry(m, recusedSecretary), HttpStatus.FORBIDDEN)).isEqualTo("RECUSED")
        assertThat(page(m, recusedSecretary)["mayRetryRecord"].asBoolean()).isFalse()
        assertThat(retry(m, colleague)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(retry(m, chair)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(retry(m, outsider)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(record(m).state).isEqualTo(RecordState.FAILED)

        val made = retry(m, secretary)
        assertThat(made).hasStatus(HttpStatus.ACCEPTED)
        assertThat(made.json()[0]["state"].asString()).isEqualTo("PENDING")
        assertThat(record(m).retries).isEqualTo(1)
        val retried = Audit.assertAudited(tenant.id, "minutes.record.retried", m.toString(), secretary.id.toString())
        assertThat(retried.canonical).contains("\"trigger\":\"SECRETARY\"", "CONVERTER_CONVERTER_M3")
        assertThat(refusalOf(retry(m, secretary), HttpStatus.CONFLICT)).describedAs("already pending").isEqualTo("RECORD_NOT_FAILED")

        server.down = false
        asSystem { records.build(m, 1, last = false) }
        assertThat(record(m).state).isEqualTo(RecordState.READY)
        assertThat(refusalOf(retry(m, secretary), HttpStatus.CONFLICT)).isEqualTo("RECORD_NOT_FAILED")
    }

    @Test
    fun `the sweep's backoff doubles from a day and stops after six tries`() {
        assertThat((0..RecordRetries.MAX_SWEEPS).map { RecordRetries.backoffDays(it) }).containsExactly(1, 2, 4, 8, 16, 32, 64)
    }

    /** The document server down for every try of the record job: FAILED after the last. */
    private fun failRecord(m: UUID) {
        server.down = true
        repeat(MinutesRecords.MAX_RETRIES - 1) {
            assertThat(runCatching { asSystem { records.build(m, 1, last = false) } }.isFailure).isTrue()
        }
        asSystem { records.build(m, 1, last = true) }
        assertThat(record(m).state).isEqualTo(RecordState.FAILED)
    }

    private fun record(m: UUID) = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) })

    private fun sweep(now: Instant): Int = asSystem { transactions.inTenant(tenant.id) { minutesSweep.sweep(now) } }

    private fun retry(
        m: UUID,
        by: Person,
    ) = step(m, "record/retry", by)

    private fun page(
        m: UUID,
        by: Person,
    ) = read("/api/v1/meetings/$m/minutes", by.session).json()

    private fun refusalOf(
        result: org.springframework.test.web.servlet.assertj.MvcTestResult,
        status: HttpStatus,
    ): String {
        assertThat(result).hasStatus(status)
        return result.json()["reason"].asString()
    }

    private fun kinds(person: Person): List<String> =
        read("/api/v1/me/notifications?limit=100", person.session).json()["items"].let { c ->
            (0 until c.size()).map { c[it]["kind"].asString() }
        }

    /**
     * Minutes with Demo Director recused from the loan (so they read an approved copy) and a second secretary recused from
     * it too, approved by circulation and confirmed by the chair: the record is asked for. Answers the recused secretary.
     */
    private fun approvedMinutes(): Pair<UUID, Person> {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val secretary2 = person().also { seat(it, ForumRole.SECRETARY) }
        val r =
            drafted(
                "Term loan, Delta Textiles",
                "Facility renewal, Acme",
                before = {
                    declare(director, it.meeting, it.items.first().item)
                    declare(secretary2, it.meeting, it.items.first().item)
                },
            )
        val m = r.meeting.id
        circulated(m)
        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatusOk()
        return m to secretary2
    }
}
