package com.memo.minutes

import com.memo.files.FileInfo
import com.memo.minutes.internal.MinutesDraftPurpose
import com.memo.minutes.internal.MinutesEventKind
import com.memo.minutes.internal.MinutesRole
import com.memo.minutes.internal.MinutesStage
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus

/**
 * The chair's review (test plan E06 T6 `MinutesReviewIT`; ADR-0066, design E06 §1.6.2): the reviewer rule (the chair; the
 * vice-chair when the chair declared an interest in an item; nobody when both did, and then circulation with a reason),
 * the review mode on the draft, the agreement recorded on the chair's behalf, and a draft sent back with comments.
 */
class MinutesReviewIT : MinutesFixture() {
    @Autowired lateinit var purpose: MinutesDraftPurpose

    @Test
    fun `the chair reviews, a return needs a comment and sends the draft back, an agreement circulates it`() {
        val r = drafted("Term loan, Delta Textiles")
        val m = r.meeting.id
        // Only secretaries send; only a sound draft goes.
        assertThat(step(m, "send-for-review", director)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(step(m, "send-for-review", secretary)).hasStatusOk()
        val sent = row(m)
        assertThat(
            Triple(sent.state, sent.reviewerId, sent.reviewerRole),
        ).isEqualTo(Triple(MinutesStage.CHAIR_REVIEW, chair.id, MinutesRole.CHAIR))
        Audit.assertAudited(tenant.id, "minutes.sent_for_review", m.toString(), secretary.id.toString())
        val asked = read("/api/v1/me/notifications", chair.session).json()["items"].first()
        assertThat(asked["kind"].asString()).isEqualTo("MINUTES_REVIEW_REQUESTED")
        assertThat(asked.toString()).doesNotContain("Term loan")
        // The chair now reads the page and is the reviewer; a member is not.
        val page = read("/api/v1/meetings/$m/minutes", chair.session)
        assertThat(page).hasStatusOk()
        assertThat(page.json()["viewerAccess"].asString()).isEqualTo("REVIEWER")
        assertThat(page.json()["myAction"].asString()).isEqualTo("REVIEW")
        assertThat(page.json().has("draft") && !page.json()["draft"].isNull).describedAs("no draft file for the chair").isFalse()
        assertThat(read("/api/v1/meetings/$m/minutes", director.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(step(m, "review", director, """{"decision":"AGREE"}""")).hasStatus(HttpStatus.FORBIDDEN)

        // Sent back: a comment is required.
        assertThat(step(m, "review", chair, """{"decision":"RETURN"}""")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(step(m, "review", chair, """{"decision":"RETURN","comment":"Add the sector outlook CANARY-RV"}"""))
            .describedAs("done; the chair reads the draft no more")
            .hasStatus(HttpStatus.NO_CONTENT)
        assertThat(row(m).state).isEqualTo(MinutesStage.DRAFT)
        val returned = Audit.assertAudited(tenant.id, "minutes.review.returned", m.toString(), chair.id.toString())
        assertThat(returned.canonical).contains("commentSha256").doesNotContain("CANARY-RV")
        assertThat(events(m).last().let { it.kind to it.note }).isEqualTo(MinutesEventKind.RETURNED to "Add the sector outlook CANARY-RV")
        assertThat(read("/api/v1/me/notifications", secretary.session).json()["items"].first()["kind"].asString())
            .isEqualTo("MINUTES_RETURNED")
        // Circulating before the chair agreed is refused.
        assertThat(step(m, "circulate", secretary).response.contentAsString).contains("NOT_AGREED")

        // Sent again and agreed: circulated at once, with the deadline.
        assertThat(step(m, "send-for-review", secretary)).hasStatusOk()
        assertThat(step(m, "review", chair, """{"decision":"AGREE"}""")).hasStatusOk()
        val agreed = row(m)
        assertThat(agreed.state).isEqualTo(MinutesStage.CIRCULATED)
        assertThat(agreed.commentsUntil).isNotNull()
        assertThat(events(m).takeLast(2).map { it.kind }).containsExactly(MinutesEventKind.AGREED, MinutesEventKind.CIRCULATED)
        Audit.assertAudited(tenant.id, "minutes.review.agreed", m.toString(), chair.id.toString())
        Audit.assertAudited(tenant.id, "minutes.circulated", m.toString(), chair.id.toString())
        // A second agreement: the minutes are with the chair no more.
        assertThat(step(m, "review", chair, """{"decision":"AGREE"}""")).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a secretary records the chair's agreement given outside the app, with how it was given`() {
        val r = drafted("Term loan, Delta Textiles")
        val m = r.meeting.id
        assertThat(step(m, "send-for-review", secretary)).hasStatusOk()
        // Without how, or sending back on the chair's behalf: 403.
        assertThat(step(m, "review", secretary, """{"decision":"AGREE"}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(step(m, "review", secretary, """{"decision":"RETURN","comment":"x","how":"EMAIL"}""")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(step(m, "review", secretary, """{"decision":"AGREE","how":"EMAIL","comment":"Email of 13 Dec"}""")).hasStatusOk()
        val agreed = events(m).single { it.kind == MinutesEventKind.AGREED }
        assertThat(listOf(agreed.actor, agreed.onBehalfBy, agreed.how)).containsExactly(chair.id, secretary.id, "EMAIL")
        val audited = Audit.assertAudited(tenant.id, "minutes.review.agreed", m.toString(), secretary.id.toString())
        assertThat(audited.canonical).contains(secretary.id.toString(), "EMAIL")
        assertThat(row(m).state).isEqualTo(MinutesStage.CIRCULATED)
    }

    @Test
    fun `the vice-chair reviews when the chair declared an interest in an item, and nobody when both did`() {
        val first = drafted("Term loan, Delta Textiles", before = { r -> declare(chair, r.meeting, previousMinutesItem(r)) })
        assertThat(step(first.meeting.id, "send-for-review", secretary)).hasStatusOk()
        assertThat(row(first.meeting.id).let { it.reviewerId to it.reviewerRole }).isEqualTo(vice.id to MinutesRole.VICE_CHAIR)

        val second =
            drafted("Working capital, Mill", before = { r ->
                declare(chair, r.meeting, previousMinutesItem(r))
                declare(vice, r.meeting, previousMinutesItem(r))
            })
        val m = second.meeting.id
        val none = step(m, "send-for-review", secretary)
        assertThat(none).hasStatus(HttpStatus.CONFLICT)
        assertThat(none.response.contentAsString).contains("NO_REVIEWER")
        // Then the secretary circulates with a reason.
        assertThat(step(m, "circulate", secretary)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(step(m, "circulate", secretary, """{"reason":"The chair and vice-chair declared interests"}""")).hasStatusOk()
        val circulated = events(m).last()
        assertThat(circulated.kind to circulated.how).isEqualTo(MinutesEventKind.CIRCULATED to "NO_REVIEWER")
        assertThat(row(m).state).isEqualTo(MinutesStage.CIRCULATED)
    }

    @Test
    fun `the reviewer reads the draft and edits it in review mode only while it is with them`() {
        val r = drafted("Term loan, Delta Textiles")
        val m = r.meeting.id
        val file = checkNotNull(transactions.readInTenant(tenant.id) { files.info(checkNotNull(row(m).fileId)) })
        assertThat(asUser(chair.account) { purpose.mayRead(file) }).describedAs("not before the review").isFalse()
        assertThat(step(m, "send-for-review", secretary)).hasStatusOk()
        assertThat(mode(chair, file)).containsExactly(true, true, true)
        // The secretary may read it but not change it while it is with the chair; a member nothing.
        assertThat(mode(secretary, file)).containsExactly(true, false, false)
        assertThat(mode(director, file)).containsExactly(false, false, false)
        // A version the chair saves (review mode) is the draft's next; one the secretary saves is refused.
        save(m, current(m), by = chair.account)
        assertThat(transactions.readInTenant(tenant.id) { files.info(file.id) }?.versionCount).isEqualTo(2)
        assertThat(runCatching { save(m, current(m)) }.isFailure).isTrue()
        // Back to the secretary: the chair no longer reads it.
        assertThat(step(m, "review", chair, """{"decision":"RETURN","comment":"See my changes"}""")).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mode(chair, file)).containsExactly(false, false, false)
    }

    /** [person]'s read, edit and review-only on [file] now. */
    private fun mode(
        person: com.memo.testsupport.Person,
        file: FileInfo,
    ): List<Boolean> = asUser(person.account) { listOf(purpose.mayRead(file), purpose.mayEdit(file), purpose.reviewOnly(file)) }
}
