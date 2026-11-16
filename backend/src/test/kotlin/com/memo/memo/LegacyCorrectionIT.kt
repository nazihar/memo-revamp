package com.memo.memo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.identity.MfaSeeding
import com.memo.identity.mfa.Totp
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.login
import com.memo.testsupport.postJson
import com.memo.testsupport.sessionCookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Corrections and classification raises of imported memos (ADR-0055, design E06 §10, §11; test plan E06 T4
 * `LegacyCorrectionIT`): each correctable field appends a correction with its value before and after and a reason, the
 * legacy facts stay as given, and only the subject reaches the memo row; the subject is searched again; a raise goes only
 * upwards, to a level the holder is cleared for, and search follows it; both need `memo.import`, the memo's read rule and a
 * fresh step-up, and both are audited.
 */
class LegacyCorrectionIT : ImportedMemoTest() {
    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var mfaSeeding: MfaSeeding

    /**
     * A holder of `memo.import` at [clearance], who reads the import's unit when [reads]. The permission is privileged
     * (holders need a second factor, ADR-0024), so they sign in with password and TOTP, and the step-up is fresh.
     */
    private fun holder(
        clearance: Int = 2,
        reads: Boolean = true,
    ): Person {
        val password = aPassword()
        val account = accounts.anActiveUser(transactions, tenant, password)
        if (clearance > 0) clear(account.id.value, clearance)
        asUser(admin) {
            access.grantCustom(account.id.value, "Importer", setOf(Permission.MEMO_IMPORT))
            if (reads) access.grantCustom(account.id.value, "Credit reader", setOf(Permission.MEMO_READ_UNIT), unit)
        }
        val secret = Totp.base32(Totp.newSecret())
        transactions.inTenant(tenant.id) { mfaSeeding.ensureTotp(account.id, secret) }
        val partial = requireNotNull(mvc.login(tenant, account.email, password).sessionCookie())
        val code = Totp.code(Totp.decodeBase32(secret), Totp.step(Instant.now()))
        return Person(
            account,
            requireNotNull(mvc.postJson(tenant, "/api/v1/auth/mfa", partial, """{"code":"$code"}""").sessionCookie(partial)),
        )
    }

    private fun unitReader(clearance: Int = 2): Person =
        person(clearance).also { asUser(admin) { access.grantCustom(it.id, "Credit reader", setOf(Permission.MEMO_READ_UNIT), unit) } }

    private fun correct(
        who: Person,
        memo: UUID,
        field: String,
        value: String?,
        reason: String = "Checked against the paper file",
    ): MvcTestResult =
        post(
            "/api/v1/memos/$memo/legacy/corrections",
            who.session,
            json.writeValueAsString(mapOf("field" to field, "value" to value, "reason" to reason)),
        )

    private fun reclassify(
        who: Person,
        memo: UUID,
        rank: Int,
        reason: String = "Contains customer data",
    ): MvcTestResult =
        post(
            "/api/v1/memos/$memo/legacy/reclassify",
            who.session,
            json.writeValueAsString(mapOf("classificationId" to levels()[rank].id.toString(), "reason" to reason)),
        )

    private fun legacyRow(
        memo: UUID,
        column: String,
    ): String? =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select $column::text from memo_legacy where memo_id = ?").use { st ->
                st.setObject(1, memo)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun searchIds(
        who: Person,
        q: String,
    ): List<String> {
        val results = post("/api/v1/search", who.session, json.writeValueAsString(mapOf("q" to q))).json()["results"]
        return (0 until results.size()).map { results[it]["memoId"].asString() }
    }

    @Test
    fun `each correctable field appends a correction with before, after and reason, and the facts stay as given`() {
        val holder = holder()
        val memo = import(row())
        val corrections =
            listOf(
                Triple("SUBJECT", "Working capital for Q2", "Working capital for Q3"),
                Triple("RAISED_ON", "2019-03-14", "2019-03-12"),
                Triple("DECIDED_ON", "2019-03-20", "2019-03-21"),
                Triple("CLOSED_ON", null, "2019-06-30"),
                Triple("INITIATOR_NAME", "Rahim Uddin", "Rahim Uddin Chowdhury"),
                Triple("LEGACY_UNIT", "Credit", "Credit Division"),
                Triple("LEGACY_TYPE", "General", "General memo"),
            )
        var version = get(memo.toString(), holder.session).json()["version"].asLong()
        corrections.forEachIndexed { i, (field, before, after) ->
            val result = correct(holder, memo, field, after)
            assertThat(result).describedAs(field).hasStatusOk()
            val view = result.json()
            assertThat(view["version"].asLong()).isEqualTo(version + 1)
            version = view["version"].asLong()
            val added = view["legacy"]["corrections"]
            assertThat(added.size()).isEqualTo(i + 1)
            val last = added[i]
            assertThat(last["field"].asString()).isEqualTo(field)
            assertThat(last["before"]?.takeUnless(JsonNode::isNull)?.asString()).isEqualTo(before)
            assertThat(last["after"].asString()).isEqualTo(after)
            assertThat(last["reason"].asString()).isEqualTo("Checked against the paper file")
            assertThat(last["correctedBy"]["id"].asString()).isEqualTo(holder.id.toString())
        }
        Audit.assertAudited(tenant.id, "memo.legacy.corrected", memo.toString(), actorId = holder.id.toString())

        // The memo row takes the subject; the legacy facts stay as given and the view shows them so.
        val view = get(memo.toString(), holder.session).json()
        assertThat(view["subject"].asString()).isEqualTo("Working capital for Q3")
        assertThat(view["legacy"]["subject"].asString()).isEqualTo("Working capital for Q2")
        assertThat(view["legacy"]["raisedOn"].asString()).isEqualTo("2019-03-14")
        assertThat(legacyRow(memo, "raised_on")).isEqualTo("2019-03-14")
        assertThat(legacyRow(memo, "initiator_name")).isEqualTo("Rahim Uddin")
        assertThat(legacyRow(memo, "subject_as_given")).isEqualTo("Working capital for Q2")
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from memo_legacy_correction where memo_id = ?", memo) })
            .isEqualTo(corrections.size.toLong())

        // Search follows: the corrected subject and name find it, as does the name as given.
        listOf("Q3", "Chowdhury", "Rahim").forEach { q -> assertThat(searchIds(holder, q)).describedAs(q).containsExactly(memo.toString()) }
        assertThat(searchIds(holder, "Q2")).isEmpty()
        // The date filter follows the corrected raised-on date.
        val found = post("/api/v1/search", holder.session, """{"q":"capital","from":"2019-03-11","to":"2019-03-12"}""").json()
        assertThat(found["results"].size()).isEqualTo(1)
    }

    @Test
    fun `holders of memo import see the actions, and wrong values are refused with the field and code`() {
        val holder = holder()
        val memo = import(row())
        val actions = get(memo.toString(), holder.session).json()["allowedActions"]
        assertThat((0 until actions.size()).map { actions[it].asString() }).contains("CORRECT_LEGACY", "RECLASSIFY")
        assertThat(get(memo.toString(), unitReader().session).json()["allowedActions"].toString()).doesNotContain("LEGACY", "RECLASSIFY")

        assertThat(correct(holder, memo, "CLASSIFICATION", "Secret").errors()).containsExactly("field:USE_RECLASSIFY")
        assertThat(correct(holder, memo, "SUBJECT", "Working capital for Q2").errors()).containsExactly("value:UNCHANGED")
        assertThat(correct(holder, memo, "SUBJECT", "x".repeat(301)).errors()).containsExactly("value:length")
        assertThat(correct(holder, memo, "INITIATOR_NAME", " ").errors()).containsExactly("value:length")
        assertThat(correct(holder, memo, "SUBJECT", "Better", reason = " ").errors()).containsExactly("reason:length")
        assertThat(correct(holder, memo, "RAISED_ON", "14/03/2019").errors()).containsExactly("value:DATE_INVALID")
        assertThat(correct(holder, memo, "DECIDED_ON", "2019-03-01").errors()).containsExactly("value:DATE_ORDER")
        assertThat(correct(holder, memo, "RAISED_ON", "2999-01-01").errors()).containsExactly("value:DATE_FUTURE")
        // An approved memo keeps a decision date.
        assertThat(correct(holder, memo, "DECIDED_ON", null).errors()).containsExactly("value:VALUE_REQUIRED")
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from memo_legacy_correction where memo_id = ?", memo) })
            .isZero()

        // A withdrawn memo needs none, so it can be cleared.
        val withdrawn = import(row(status = LegacyStatus.WITHDRAWN))
        assertThat(correct(holder, withdrawn, "DECIDED_ON", null)).hasStatusOk()

        // A memo raised here has no legacy facts.
        val native = UUID.fromString(aDraft(holder))
        val refused = correct(holder, native, "SUBJECT", "Other")
        assertThat(refused).hasStatus(HttpStatus.CONFLICT)
        assertThat(refused.json()["reason"].asString()).isEqualTo("NOT_IMPORTED")
    }

    @Test
    fun `a raise goes only upwards, to a level the holder is cleared for, and search follows it`() {
        val holder = holder(clearance = 1)
        val memo = import(row(rank = 0))
        val lowReader = unitReader(clearance = 0)
        assertThat(searchIds(lowReader, "Rahim")).containsExactly(memo.toString())

        assertThat(reclassify(holder, memo, 0).errors()).containsExactly("classificationId:CLASSIFICATION_NOT_HIGHER")
        assertThat(reclassify(holder, memo, 2).errors()).containsExactly("classificationId:above-clearance")
        assertThat(reclassify(holder, memo, 1, reason = "").errors()).containsExactly("reason:length")

        val raised = reclassify(holder, memo, 1)
        assertThat(raised).hasStatusOk()
        val view = raised.json()
        assertThat(view["classification"]["rank"].asInt()).isEqualTo(1)
        val correction = view["legacy"]["corrections"][0]
        assertThat(correction["field"].asString()).isEqualTo("CLASSIFICATION")
        assertThat(correction["before"].asString()).isEqualTo(levels()[0].name)
        assertThat(correction["after"].asString()).isEqualTo(levels()[1].name)
        val audited = Audit.assertAudited(tenant.id, "memo.legacy.reclassified", memo.toString(), actorId = holder.id.toString())
        assertThat(audited.canonical).contains(levels()[1].id.toString(), "Contains customer data")

        // A lower level is refused now, also by the database's guard.
        assertThat(reclassify(holder, memo, 0).errors()).containsExactly("classificationId:CLASSIFICATION_NOT_HIGHER")
        // Readers below the new level lose it, in the memo and in search.
        assertThat(get(memo.toString(), lowReader.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(searchIds(lowReader, "Rahim")).isEmpty()
        assertThat(searchIds(holder, "Rahim")).containsExactly(memo.toString())
    }

    @Test
    fun `both need memo import, the memo's read rule and a fresh step-up`() {
        val memo = import(row())
        // A reader without the permission: 403 before anything is read.
        val reader = unitReader()
        assertThat(correct(reader, memo, "SUBJECT", "Other")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(reclassify(reader, memo, 1)).hasStatus(HttpStatus.FORBIDDEN)
        // A holder who does not read the memo: 404, as for any memo they cannot open.
        val outsider = holder(reads = false)
        assertThat(correct(outsider, memo, "SUBJECT", "Other")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(reclassify(outsider, memo, 1)).hasStatus(HttpStatus.NOT_FOUND)
        // A holder whose second factor is old: step up first.
        val stale = holder()
        ageSessionFactors(stale.session)
        listOf(correct(stale, memo, "SUBJECT", "Other"), reclassify(stale, memo, 1)).forEach { refused ->
            assertThat(refused).hasStatus(HttpStatus.UNAUTHORIZED)
            assertThat(refused.json()["type"].asString()).isEqualTo("urn:memo:problem:step-up-required")
        }
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { it.long("select count(*) from memo_legacy_correction where memo_id = ?", memo) })
            .isZero()
    }
}
