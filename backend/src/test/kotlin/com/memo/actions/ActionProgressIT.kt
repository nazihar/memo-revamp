package com.memo.actions

import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.Confidence
import com.memo.actions.internal.How
import com.memo.actions.internal.OnBehalf
import com.memo.actions.internal.RagColour
import com.memo.actions.internal.RagWord
import com.memo.actions.internal.UpdateInput
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Progress on an issued action (test plan E07 T4 `ActionProgressIT`; design E07 §1.5, §1.6): updates by the owner with
 * their confidence, by a contributor, and by a secretary for the owner with how they heard; corrections as new entries;
 * the evidence checks; "I have started"; OPEN → IN_PROGRESS; the stale rule reset; the override, cleared by the next
 * update unless it has a date (T4's acceptance criterion); red, amber and green in words on the page (Kotlin) and in the
 * lists (SQL); the timeline without the hashes.
 */
class ActionProgressIT : ActionFixture() {
    override fun label() = "actprogress"

    private fun update(
        id: UUID,
        input: UpdateInput,
    ) = progress.update(id, row(id).version, input)

    @Test
    fun `the owner, a contributor and a secretary for the owner post updates, and the owner's word moves the colour`() {
        val action = issued(contributors = listOf(others[0].id.value))
        val id = action.id
        assertThat(action.status to action.staleFrom).matches { (s, stale) -> s == ActionStatus.OPEN && stale != null }

        // The owner: OPEN → IN_PROGRESS, at risk (amber), the stale rule counted again from today.
        val seq = asUser(director2) { update(id, UpdateInput("Valuer appointed; report expected next week.", Confidence.AT_RISK)) }
        val updated = row(id)
        assertThat(updated.status to updated.confidence).isEqualTo(ActionStatus.IN_PROGRESS to Confidence.AT_RISK)
        assertThat(updated.lastUpdateAt).isNotNull()
        val page = checkNotNull(seen(id, director2))
        assertThat(page.rag.colour to page.rag.word).isEqualTo(RagColour.AMBER to RagWord.AT_RISK)
        // The list reads the same rule in SQL.
        assertThat(
            mine(director2)
                .items
                .single()
                .rag.word,
        ).isEqualTo(RagWord.AT_RISK)
        val posted = Audit.assertAudited(tenant.id, "actions.update.posted", id.toString(), director2.id.value.toString())
        assertThat(posted.canonical).contains("AT_RISK", "textSha256").doesNotContain("Valuer")

        // A contributor writes, but does not say how it is going.
        assertThat(outcome(others[0]) { update(id, UpdateInput("Data sent to the valuer.", Confidence.ON_TRACK)) })
            .isEqualTo("422 CONFIDENCE")
        asUser(others[0]) { update(id, UpdateInput("Data sent to the valuer.")) }
        assertThat(row(id).confidence).isEqualTo(Confidence.AT_RISK)

        // A secretary writes for the owner, saying how they heard; a member who is not involved cannot write.
        assertThat(outcome(secretary) { update(id, UpdateInput("Owner called.", Confidence.OFF_TRACK, onBehalfOf = director2.id.value)) })
            .isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { update(id, UpdateInput("Owner called.", how = How.PHONE, onBehalfOf = chair.id.value)) })
            .isEqualTo("422 ON_BEHALF_OF")
        assertThat(outcome(secretary) { update(id, UpdateInput("Owner called.")) }).isEqualTo("403")
        asUser(secretary) {
            update(
                id,
                UpdateInput("Owner called: the valuer is late.", Confidence.OFF_TRACK, onBehalfOf = director2.id.value, how = How.PHONE),
            )
        }
        assertThat(checkNotNull(seen(id, secretary)).rag.word).isEqualTo(RagWord.OFF_TRACK)
        assertThat(outcome(others[1]) { update(id, UpdateInput("Me too.")) }).isEqualTo("403")
        assertThat(outcome(user) { update(id, UpdateInput("Me too.")) }).isEqualTo("404")
        assertThat(outcome(director2) { update(id, UpdateInput(" ")) }).isEqualTo("422 REQUIRED")

        // Corrections are new entries pointing at the earlier one, the author's own only.
        val corrected = asUser(director2) { update(id, UpdateInput("Valuer appointed; report expected in two weeks.", corrects = seq)) }
        assertThat(outcome(others[0]) { update(id, UpdateInput("No.", corrects = seq)) }).isEqualTo("422 CORRECTS")
        val entries = asUser(director2) { actionAccess.entries(id) }.entries
        assertThat(entries.first().seq to entries.first().corrects).isEqualTo(corrected to seq)
        val behalf = entries.single { it.onBehalfOf != null }
        assertThat(behalf.actor?.id to behalf.onBehalfOf?.id).isEqualTo(secretary.id.value to director2.id.value)
        assertThat(behalf.how to behalf.text).isEqualTo(How.PHONE to "Owner called: the valuer is late.")
        assertThat(entries.flatMap { it.details.keys }).noneMatch { it.endsWith("Sha256") }
        assertThat(entries.map { it.kind }).containsSubsequence("UPDATE", "UPDATE", "ISSUED", "DRAFTED")
        // The timeline is the action's: 404 to whoever does not see it.
        assertThat(outcome(user) { actionAccess.entries(id) }).isEqualTo("404")

        // Evidence: at most ten files, each an evidence file of this action uploaded by the writer.
        assertThat(outcome(director2) { update(id, UpdateInput("See attached.", fileIds = List(11) { UUID.randomUUID() })) })
            .isEqualTo("422 TOO_MANY")
        assertThat(outcome(director2) { update(id, UpdateInput("See attached.", fileIds = listOf(UUID.randomUUID()))) })
            .isEqualTo("422 unknown")
    }

    @Test
    fun `I have started moves an open action on, once, by its owner`() {
        val id = issued(contributors = listOf(others[0].id.value)).id
        assertThat(outcome(others[0]) { progress.started(id, row(id).version, OnBehalf()) }).isEqualTo("403")
        assertThat(outcome(secretary) { progress.started(id, row(id).version, OnBehalf(director2.id.value)) }).isEqualTo("422 REQUIRED")
        asUser(director2) { progress.started(id, row(id).version, OnBehalf()) }
        assertThat(row(id).status).isEqualTo(ActionStatus.IN_PROGRESS)
        assertThat(timeline(id).first()).isEqualTo("STARTED")
        Audit.assertAudited(tenant.id, "actions.started", id.toString(), director2.id.value.toString())
        assertThat(outcome(director2) { progress.started(id, row(id).version, OnBehalf()) }).isEqualTo("409 WRONG_STATE")
        // A stale version is a conflict, not a second change.
        assertThat(outcome(director2) { progress.update(id, 0, UpdateInput("Late news.")) }).isEqualTo("409 CONFLICT")
    }

    @Test
    fun `an override without a date is cleared by the next update, one with a date stays, and both are audited`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), secretary2) }
        val id = issued(deciding = d).id
        val reason = "The valuer has withdrawn"
        asUser(secretary) { progress.override(id, row(id).version, RagColour.RED, reason, null) }
        val red = checkNotNull(seen(id, director2)).rag
        assertThat(red.colour to red.word).isEqualTo(RagColour.RED to RagWord.OVERRIDE_RED)
        assertThat(red.override?.by?.id to red.override?.reason).isEqualTo(secretary.id.value to reason)
        assertThat(
            mine(director2)
                .items
                .single()
                .rag.word,
        ).isEqualTo(RagWord.OVERRIDE_RED)
        val overridden = Audit.assertAudited(tenant.id, "actions.rag.overridden", id.toString(), secretary.id.value.toString())
        assertThat(overridden.canonical).contains("reasonSha256").doesNotContain("valuer")

        // T4: a RAG override without a date is cleared by the next update.
        asUser(director2) { update(id, UpdateInput("A new valuer starts on Monday.")) }
        assertThat(row(id).ragOverride).isNull()
        assertThat(checkNotNull(seen(id, director2)).rag.word).isEqualTo(RagWord.ON_TRACK)
        Audit.assertAudited(tenant.id, "actions.rag.cleared", id.toString(), director2.id.value.toString())

        // With a date it stays through updates, until an officer clears it.
        val until = LocalDate.now(ZoneOffset.UTC).plusDays(7)
        asUser(chair) { progress.override(id, row(id).version, RagColour.GREEN, "Agreed at the meeting", until) }
        asUser(director2) { update(id, UpdateInput("On track.")) }
        assertThat(row(id).ragOverride to row(id).ragOverrideUntil).isEqualTo(RagColour.GREEN to until)
        asUser(secretary) { progress.clear(id, row(id).version) }
        assertThat(row(id).ragOverride).isNull()
        assertThat(timeline(id).first()).isEqualTo("RAG_CLEARED")
        assertThat(outcome(secretary) { progress.clear(id, row(id).version) }).isEqualTo("409 NO_OVERRIDE")

        // Officers only, not recused, with a reason and a colour.
        assertThat(outcome(others[0]) { progress.override(id, row(id).version, RagColour.RED, reason, null) }).isEqualTo("403")
        assertThat(outcome(director2) { progress.override(id, row(id).version, RagColour.GREEN, reason, null) }).isEqualTo("403")
        assertThat(outcome(secretary2) { progress.override(id, row(id).version, RagColour.RED, reason, null) }).isEqualTo("404")
        assertThat(outcome(secretary) { progress.override(id, row(id).version, RagColour.RED, " ", null) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { progress.override(id, row(id).version, RagColour.BLUE, reason, null) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(secretary) { progress.override(id, row(id).version, RagColour.RED, reason, until.minusDays(30)) })
            .isEqualTo("422 DUE_IN_PAST")
    }

    @Test
    fun `an action without a recent update, or due soon, is amber in words on its page and in the lists`() {
        val id = issued().id
        // As if issued long ago: "No recent update" from yesterday.
        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        alter(id, "stale_from = ?", yesterday)
        assertThat(checkNotNull(seen(id, director2)).rag.word).isEqualTo(RagWord.NO_RECENT_UPDATE)
        assertThat(
            mine(director2)
                .items
                .single()
                .rag.word,
        ).isEqualTo(RagWord.NO_RECENT_UPDATE)
        // An update resets it.
        asUser(director2) { update(id, UpdateInput("Still on it.")) }
        assertThat(checkNotNull(seen(id, director2)).rag.word).isEqualTo(RagWord.ON_TRACK)
        alter(id, "amber_from = ?", yesterday)
        assertThat(meetingActionsRag(id)).isEqualTo(RagWord.DUE_SOON)
        assertThat(checkNotNull(seen(id, director2)).rag.word).isEqualTo(RagWord.DUE_SOON)
    }

    private fun meetingActionsRag(id: UUID): RagWord {
        val meeting = checkNotNull(row(id).meetingId)
        return asUser(secretary) { actionAccess.ofMeeting(meeting) }
            .actions
            .single { it.id == id }
            .rag.word
    }
}
