package com.memo.workflow

import com.memo.identity.MfaSeeding
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.adminSession
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * The business calendar API (design E05 §5, test plan T1): the default week until someone sets one, If-Match on the
 * weekly hours, holidays and other special days set one at a time or as a range, and a preview of due times; audited.
 */
class BusinessCalendarIT : MemoIntegrationTest() {
    private val base = "/api/v1/admin/workflow/calendar"

    @Test
    fun `the default week, then hours set with If-Match`() {
        val session = adminSession()
        val first = read(base, session)
        assertThat(first.json()["defaulted"].asBoolean()).isTrue()
        assertThat(first.json()["week"]["SUNDAY"][0][0].asString()).isEqualTo("09:00")

        val body = """{"week":{"SUNDAY":[["09:00","13:00"],["14:00","17:00"]],"MONDAY":[["09:00","17:00"]]}}"""
        assertThat(put(base, session, body, 3)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        val saved = put(base, session, body, 0)
        assertThat(saved).hasStatusOk()
        assertThat(saved.json()["defaulted"].asBoolean()).isFalse()
        assertThat(saved.json()["week"]["SUNDAY"].size()).isEqualTo(2)
        assertThat(saved.json()["week"].has("THURSDAY")).isFalse()
        Audit.assertAudited(tenant.id, "workflow.calendar.updated", tenant.id.toString())

        val overlapping = """{"week":{"SUNDAY":[["09:00","13:00"],["12:00","17:00"]]}}"""
        assertThat(put(base, session, overlapping, 1)).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `holidays and ranges move due times, and a day can follow the week again`() {
        val session = adminSession()
        // 2026-11-12 is a Thursday; Friday and Saturday are off by default.
        val preview = """{"start":"2026-11-12T09:00:00Z","due":"P1D"}"""
        assertThat(post("$base/preview", session, preview).json()["due"].asString()).isEqualTo("2026-11-15T09:00:00Z")

        val eid = post("$base/days", session, """{"from":"2026-11-15","to":"2026-11-17","note":"Eid"}""")
        assertThat(eid).hasStatusOk()
        assertThat(eid.json().size()).isEqualTo(3)
        Audit.assertAudited(tenant.id, "workflow.calendar.day_set", tenant.id.toString())
        assertThat(post("$base/preview", session, preview).json()["due"].asString()).isEqualTo("2026-11-18T09:00:00Z")

        val listed = read("$base/days?from=2026-11-01&to=2026-11-30", session)
        assertThat(listed.json().size()).isEqualTo(3)
        assertThat(listed.json()[0]["note"].asString()).isEqualTo("Eid")

        val removed = mvc.sendJson(HttpMethod.DELETE, tenant, "$base/days/2026-11-15", session)
        assertThat(removed).hasStatus(HttpStatus.NO_CONTENT)
        Audit.assertAudited(tenant.id, "workflow.calendar.day_removed", tenant.id.toString())
        assertThat(post("$base/preview", session, preview).json()["due"].asString()).isEqualTo("2026-11-15T09:00:00Z")

        assertThat(post("$base/days", session, """{"from":"2026-01-01","to":"2027-06-01"}""")).hasStatus(HttpStatus.BAD_REQUEST)
        assertThat(post("$base/preview", session, """{"due":"two days"}""")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    @Autowired lateinit var mfaSeeding: MfaSeeding

    /** A tenant administrator past MFA (they hold `workflow.calendar.manage`). */
    private fun adminSession(): Cookie = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
}
