package com.memo.minutes

import com.memo.forum.MinutesApproval
import com.memo.minutes.internal.CopyStage
import com.memo.minutes.internal.CopyState
import com.memo.minutes.internal.MinutesCopyStore
import com.memo.minutes.internal.MinutesRecordStore
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.RecordState
import com.memo.testsupport.CachingDocumentServer
import com.memo.testsupport.FakeRenditionServices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * A conversion that failed while the document server restarted is tried again under a fresh key (found on ONLYOFFICE 10 in
 * E06 T12): the document server keeps each key's answer, failures included, so a retry under the first attempt's key got
 * the failure back at once and the copy or record failed for good. Here the application's ONLYOFFICE client talks to a
 * stand-in that caches by key the same way; the minutes' document goes there, the rest stays on the fake.
 */
class ConversionRetryIT : MinutesFixture() {
    @Autowired lateinit var records: MinutesRecords

    @Autowired lateinit var recordStore: MinutesRecordStore

    @Autowired lateinit var copyStore: MinutesCopyStore

    private val server = CachingDocumentServer(FakeRenditionServices::converted)

    @BeforeEach
    fun route() {
        FakeRenditionServices.documentServer =
            FakeRenditionServices.Route(
                setOf(FakeRenditionServices.MINUTES_COPY, FakeRenditionServices.MINUTES_RECORD),
                server.converter(),
            )
    }

    @AfterEach
    fun unroute() {
        FakeRenditionServices.documentServer = null
        server.close()
    }

    @Test
    fun `a reader's copy that failed during an outage is built on the job's next try`() {
        val m = drafted("Facility renewal, Acme").meeting.id
        circulated(m)
        server.down = true
        assertThat(runCatching { asSystem { minutesCopies.build(m, last = false) } }.isFailure).isTrue()
        val pending = transactions.readInTenant(tenant.id) { copyStore.ofVersion(m, CopyStage.DRAFT, sourceVersion(m)) }
        assertThat(pending.map { it.state }.distinct()).containsExactly(CopyState.PENDING)

        server.down = false
        asSystem { minutesCopies.build(m, last = true) }
        val built = transactions.readInTenant(tenant.id) { copyStore.ofVersion(m, CopyStage.DRAFT, sourceVersion(m)) }
        assertThat(built.map { it.state }.distinct()).describedAs(built.joinToString { "${it.state} ${it.error}" }).containsExactly(
            CopyState.DONE,
        )
        assertFreshKeys(built.map { "minutes-copy-${it.id}" })
    }

    @Test
    fun `a record that failed during an outage is made on the job's next try`() {
        val m = approvedMinutes()
        server.down = true
        assertThat(runCatching { asSystem { records.build(m, 1, last = false) } }.isFailure).isTrue()
        assertThat(checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) }).state).isEqualTo(RecordState.PENDING)

        server.down = false
        asSystem { records.build(m, 1, last = true) }
        val record = checkNotNull(transactions.readInTenant(tenant.id) { recordStore.find(m, 1) })
        assertThat(record.state to record.error).isEqualTo(RecordState.READY to null)
        assertThat(record.compliant).isTrue()
        assertFreshKeys(listOf("minutes-record-${record.id}"))
    }

    /** Each caller's key went out once a try, under a new key each time, every one a valid ONLYOFFICE key. */
    private fun assertFreshKeys(callerKeys: List<String>) {
        callerKeys.forEach { callerKey ->
            val sent = server.keys.filter { it.startsWith(callerKey) }.distinct()
            assertThat(sent).describedAs("keys sent for $callerKey in ${server.keys}").hasSize(2)
        }
        assertThat(server.keys).allSatisfy { assertThat(it).hasSizeLessThanOrEqualTo(128).matches("[0-9A-Za-z._=-]+") }
    }

    private fun sourceVersion(meetingId: UUID) =
        checkNotNull(transactions.readInTenant(tenant.id) { files.info(checkNotNull(row(meetingId).fileId))?.currentVersionId })

    /** Minutes approved by circulation and confirmed by the chair: the record is asked for. */
    private fun approvedMinutes(): UUID {
        minutesApproved(MinutesApproval.BY_CIRCULATION)
        val m = drafted("Facility renewal, Acme").meeting.id
        circulated(m)
        assertThat(step(m, "end-circulation", secretary, """{"reason":"Needed for the drawdown"}""")).hasStatusOk()
        assertThat(step(m, "confirm", chair)).hasStatusOk()
        return m
    }
}
