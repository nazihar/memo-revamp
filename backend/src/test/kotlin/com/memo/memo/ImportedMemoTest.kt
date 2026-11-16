package com.memo.memo

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.legacy.MemoLegacyPurpose
import com.memo.org.OrgSeeding
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID

/**
 * Imported memos in tests (design E06 §3.1): a unit to import into, originals stored as the import job stores them
 * (purpose `memo.legacy`, the system actor, the memo's reserved id as owner), and rows built with sensible defaults.
 */
abstract class ImportedMemoTest : MemoIntegrationTest() {
    @Autowired lateinit var legacy: LegacyMemos

    @Autowired lateinit var fileUploads: FileUploads

    @Autowired lateinit var files: Files

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    val entity: UUID = UUID.randomUUID()
    val unit: UUID = UUID.randomUUID()
    val batch: UUID = UUID.randomUUID()
    val zone: ZoneId = ZoneId.of("Asia/Dhaka")
    lateinit var importer: Person
    private var rows = 0

    @BeforeEach
    fun legacyOrganisation() {
        val since = LocalDate.now().minusYears(1)
        asUser(admin) {
            orgSeeding.legalEntity(entity, "Bank", since)
            orgSeeding.unit(unit, entity, null, "CRD", "Credit Division", since)
        }
        importer = person(clearance = 2)
    }

    fun generalTypeId(): UUID = asUser(admin) { memoTypes.active().first { it.key == "general" }.id }

    /** As the import job runs: the tenant bound and the job's system actor (the only one that stores originals). */
    fun <T> asJob(block: () -> T): T = TenantContext.call(tenant.id) { ActorContext.asSystem(MemoLegacyPurpose.JOB, block) }

    /** Stores [bytes] as an original of [memoId], as the import's promotion does; returns the file's id. */
    fun original(
        memoId: UUID,
        bytes: ByteArray = Samples.pdf(),
        name: String = "memo.pdf",
    ): UUID {
        val version = asJob { fileUploads.upload(MemoLegacyPurpose.KEY, memoId.toString(), name, bytes, UUID.randomUUID().toString()) }
        return transactions.readInTenant(tenant.id) { checkNotNull(files.version(version)).fileId }
    }

    /** A row for a new memo id, its main original stored unless [stored] is false. */
    fun row(
        number: String = "LEG/2019/${rows + 1}",
        status: LegacyStatus = LegacyStatus.APPROVED,
        rank: Int = 0,
        initiator: LegacyPerson = LegacyPerson("Rahim Uddin"),
        approvals: List<LegacyApproval> = listOf(LegacyApproval(1, LegacyPerson("Karim Ahmed"), "Approved", "Head of Credit")),
        dates: LegacyDates = LegacyDates(LocalDate.of(2019, 3, 14), LocalDate.of(2019, 3, 20), closedOn(status)),
        stored: Boolean = true,
        /** The stored original's file name (the test text extractor answers "Text of <name>"). */
        originalName: String = "memo.pdf",
    ): LegacyMemoRow {
        val memoId = UUID.randomUUID()
        rows++
        val main =
            LegacyOriginal(
                LegacyFileRole.MAIN,
                "Memo",
                "memos/$number.pdf",
                fileId = if (stored) original(memoId, name = originalName) else null,
            )
        return LegacyMemoRow(
            memoId = memoId,
            source = LegacySource(batch, rows, "LEGACY-DMS", number, fingerprint(number), zone, importer.id),
            given = LegacyAsGiven(status.name.lowercase(), "General", "Credit", "Working capital for Q2", "Internal"),
            mapped = LegacyMapped(generalTypeId(), status, levels()[rank].id, unit, number),
            dates = dates,
            initiator = initiator,
            approvals = approvals,
            originals = listOf(main),
        )
    }

    /** Imports [row] in a transaction of its own, as the import job; returns the memo's id. */
    fun import(row: LegacyMemoRow): UUID = asJob { transactions.inTenant(tenant.id) { legacy.create(row) } }

    fun fingerprint(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun closedOn(status: LegacyStatus): LocalDate? = LocalDate.of(2019, 6, 30).takeIf { status == LegacyStatus.CLOSED }
}
