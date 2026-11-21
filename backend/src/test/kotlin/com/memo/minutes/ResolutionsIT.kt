package com.memo.minutes

import com.memo.common.authz.Permission
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.meeting.internal.ItemChange
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.sendJson
import com.memo.testsupport.sessionCookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * The resolution API (test plan E06 T4 `ResolutionCounterStartIT`, `ResolutionRegisterIT`; design E06 §1.3, §1.9, §3):
 * the starting number set once by `forums.manage` with a fresh step-up; the register in number order, by keyset pages,
 * a row the viewer does not see in full "restricted"; the check; one resolution for whoever sees its decision and for the
 * readers of its memo, and nobody else.
 */
class ResolutionsIT : ReleaseFixture() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Test
    fun `the starting number is set once, by forums manage with a fresh step-up, and the register and its check follow it`() {
        val year =
            java.time.LocalDate
                .now(java.time.ZoneId.of("Asia/Dhaka"))
                .year
        startAt14(year)

        var restricted: UUID? = null
        val r =
            running("Term loan, Delta Textiles", "Facility renewal, Acme") { meeting ->
                val item = addItem(meeting, "Settlement with a counterparty")
                change(meeting, item, ItemChange("Settlement with a counterparty", restrictedTo = listOf(director.id)))
                restricted = item
            }
        val (first, second) = r.items
        val hidden = checkNotNull(restricted)
        decide(r, first.item, APPROVED_WITH_CONDITIONS)
        decide(r, second.item, APPROVED)
        decide(r, hidden, APPROVED.copy(text = "It was RESOLVED that the settlement be approved."))
        held(r)
        // The two submissions in agenda order; the secretary's item wherever its section puts it.
        val given = listOf(first.item, second.item, hidden).associateWith { checkNotNull(numberOf(it)) }
        assertThat(given.values).containsExactlyInAnyOrder("BRC/$year/014", "BRC/$year/015", "BRC/$year/016")
        assertThat(given.getValue(first.item)).isLessThan(given.getValue(second.item))

        // The register, the latest first, two to a page.
        val page1 = read("/api/v1/forums/$brc/resolutions?limit=2", secretary.session).json()
        assertThat(page1["period"].asString()).isEqualTo("$year")
        assertThat(page1["entries"].items().map { it["number"].asString() }).containsExactly("BRC/$year/016", "BRC/$year/015")
        val page2 = read("/api/v1/forums/$brc/resolutions?limit=2&cursor=${page1["next"].asString()}", secretary.session).json()
        assertThat(page2["entries"].items().map { it["number"].asString() }).containsExactly("BRC/$year/014")
        assertThat(page2.absent("next")).isTrue()
        val all = (page1["entries"].items() + page2["entries"].items()).associateBy { it["number"].asString() }
        val full = all.getValue(given.getValue(first.item))
        assertThat(full["restricted"].asBoolean()).isFalse()
        assertThat(full["itemTitle"].asString() to full["outcomeLabel"].asString())
            .isEqualTo("Term loan, Delta Textiles" to "Approved with conditions")
        // The memo is named only to those who read it (ADR-0050): the committee's readers retire once it is decided.
        assertThat(full.absent("memo")).isTrue()

        // forum.read reads the restricted item's number, meeting and item number only.
        val ofReader = read("/api/v1/forums/$brc/resolutions", reader.session)
        assertThat(ofReader).hasStatusOk()
        assertThat(ofReader.response.contentAsString).doesNotContain("Settlement", "settlement")
        val line = ofReader.json()["entries"].items().single { it["number"].asString() == given.getValue(hidden) }
        assertThat(line["restricted"].asBoolean()).isTrue()
        listOf("itemTitle", "outcome", "outcomeLabel", "memo").forEach { assertThat(line.absent(it)).describedAs(it).isTrue() }
        // A member of the committee is not given the register; someone outside it does not know of it.
        assertThat(read("/api/v1/forums/$brc/resolutions", colleague.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/forums/$brc/resolutions", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)

        // The check: 14 to 16, all accounted for.
        val check = read("/api/v1/forums/$brc/resolutions/check?period=$year", secretary.session).json()
        assertThat(check["firstNumber"].asLong() to check["lastNumber"].asLong()).isEqualTo(14L to 16L)
        assertThat(check["inForce"].asInt() to check["complete"].asBoolean()).isEqualTo(3 to true)
        assertThat(read("/api/v1/forums/$brc/resolutions/check?period=1999", secretary.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `one resolution is read by those who see its decision and by its memo's readers, and by nobody else`() {
        var restricted: UUID? = null
        val r =
            running("Term loan, Delta Textiles") { meeting ->
                val item = addItem(meeting, "Settlement with a counterparty")
                change(meeting, item, ItemChange("Settlement with a counterparty", restrictedTo = listOf(director.id)))
                restricted = item
            }
        val credit = r.items.single()
        decide(r, credit.item, APPROVED_WITH_CONDITIONS)
        decide(r, checkNotNull(restricted), APPROVED.copy(text = "It was RESOLVED that the settlement be approved."))
        held(r)
        val id = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(credit.item) }?.resolutionId)
        val hiddenId = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.ofItem(checkNotNull(restricted)) }?.resolutionId)

        // A member who sees the item reads it in full.
        val ofMember = read("/api/v1/resolutions/$id", vice.session)
        assertThat(ofMember).hasStatusOk()
        assertThat(ofMember.json()["full"].asBoolean() to ofMember.json()["itemTitle"].asString())
            .isEqualTo(true to "Term loan, Delta Textiles")
        // A reader of the memo outside the meeting (its reviewer) reads the outcome, the number, the text and the conditions,
        // not the item.
        val ofInitiator = read("/api/v1/resolutions/$id", reviewer.session).json()
        assertThat(ofInitiator["full"].asBoolean()).isFalse()
        assertThat(ofInitiator.absent("itemTitle")).isTrue()
        assertThat(ofInitiator["conditions"].items().map { it.asString() }).containsExactly("Security perfected before disbursement.")
        assertThat(ofInitiator["memo"]["id"].asString()).isEqualTo(credit.memo.toString())
        // Nobody else.
        assertThat(read("/api/v1/resolutions/$id", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/resolutions/$hiddenId", vice.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/resolutions/$hiddenId", director.session)).hasStatusOk()
        assertThat(read("/api/v1/resolutions/${UUID.randomUUID()}", secretary.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    /** The numbers of [year] start at 14: refused to a secretary, outside, for a wrong period or number, a stale step-up; then once. */
    private fun startAt14(year: Int) {
        val manager = manager()
        val counter = "/api/v1/forums/$brc/resolution-counters/$year"
        assertThat(putCounter(secretary, counter, 14)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(putCounter(outsider, counter, 14)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(
            putCounter(manager, "/api/v1/forums/$brc/resolution-counters/FY$year-27", 14),
        ).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(putCounter(manager, counter, 0)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val stale = manager()
        ageSessionFactors(stale.session)
        val refused = putCounter(stale, counter, 14)
        assertThat(refused).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(refused.json()["type"].asString()).isEqualTo("urn:memo:problem:step-up-required")
        assertThat(putCounter(manager, counter, 14)).hasStatus(HttpStatus.NO_CONTENT)
        Audit.assertAudited(tenant.id, "minutes.resolution.counter_started", brc.toString(), manager.id.toString())
        val again = putCounter(manager, counter, 20)
        assertThat(again).hasStatus(HttpStatus.CONFLICT)
        assertThat(again.json()["reason"].asString()).isEqualTo("ALREADY_STARTED")
    }

    private fun putCounter(
        who: Person,
        uri: String,
        next: Long,
    ) = mvc.sendJson(
        HttpMethod.PUT,
        tenant,
        uri,
        who.session,
        """{"next":$next}""",
        mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
    )

    /** A `forums.manage` holder signed in with password and TOTP (the permission is privileged), so a step-up is fresh. */
    private fun manager(): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        asUser(admin) { grants.grantCustom(account.id.value, "Committee administration", setOf(Permission.FORUMS_MANAGE)) }
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, account.email, password).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return Person(
            account,
            requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial)),
        )
    }
}
