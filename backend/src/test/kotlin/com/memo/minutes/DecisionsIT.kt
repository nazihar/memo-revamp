package com.memo.minutes

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.PreconditionRequiredException
import com.memo.forum.OutcomeKind
import com.memo.meeting.QuorumSnapshots
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.VoteMode
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Recording, correcting and reading decisions (test plan E06 T3 `DecisionsIT`; ADR-0065, design E06 §1.1, §1.2): the
 * acceptance criteria of T3 on a running BRC meeting (7 voting members, one recused from the item), corrections with their
 * history, the release fixing the outcome, a consent section in one action, the notes' versions, the time defaults, E05's
 * override and its ratification, and who may do what.
 */
class DecisionsIT : DecisionFixture() {
    @Autowired lateinit var snapshots: QuorumSnapshots

    @Autowired lateinit var tenantDsl: com.memo.common.tenant.TenantDsl

    override fun label() = "decisions"

    private val approvedWithConditions =
        DecisionInput(
            outcome = OutcomeKind.APPROVED_WITH_CONDITIONS,
            text = "It was RESOLVED that the term loan of BDT 60 million be approved.",
            conditions = listOf("Security perfected before disbursement"),
            validUntil = null,
            voteMode = VoteMode.VOTE,
            votesFor = 4,
            votesAgainst = 1,
            votesAbstain = 0,
        )

    @Test
    fun `a decision with conditions, a vote and a named dissent takes a quorate snapshot, and took no part is the recused member`() {
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), director) }
        // Six of the seven arrive, the recused director among them (in the room, recused): five may vote.
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1], others[2]))
        val input = approvedWithConditions.copy(dissent = listOf(Dissent(director2.id.value, "The sector outlook is too weak")))
        val recorded = record(d, d.credit, input)

        assertThat(recorded.entry.seq to recorded.entry.outcomeLabel).isEqualTo(1 to "Approved with conditions")
        assertThat(recorded.entry.tookNoPart).containsExactly(director.id.value)
        val snapshot = checkNotNull(asUser(secretary) { snapshots.find(recorded.entry.snapshotId) })
        assertThat(snapshot.quorate to snapshot.itemId).isEqualTo(true to d.credit)
        assertThat(recorded.entry.ratificationRequired).isFalse()
        val audit = Audit.assertAudited(tenant.id, "minutes.decision.recorded", d.meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical)
            .contains(recorded.decision.id.toString(), "APPROVED_WITH_CONDITIONS", "textSha256", director2.id.value.toString())
            .doesNotContain("BDT 60 million", "sector outlook")

        // The secretary's view: the vote, the names and who recorded it.
        val view = asUser(secretary) { minutesAccess.decision(d.meeting.id, d.credit) }
        assertThat(view.vote?.votesFor to view.dissent?.single()?.reason).isEqualTo(4 to "The sector outlook is too weak")
        assertThat(view.tookNoPart?.map { it.id }).containsExactly(director.id.value)
        assertThat(view.recordedBy?.id).isEqualTo(secretary.id.value)
        // The recused director's view: the outcome, the text and the conditions, no vote and no names.
        val recused = asUser(director) { minutesAccess.decision(d.meeting.id, d.credit) }
        assertThat(recused.viewerRecused to recused.text).isEqualTo(true to input.text)
        assertThat(listOf(recused.vote, recused.dissent, recused.abstentions, recused.tookNoPart, recused.recordedBy)).containsOnlyNulls()
        // A member sees it in full, without who recorded it; forum.read reads the unrestricted item's decision in full.
        val member = asUser(others[0]) { minutesAccess.decision(d.meeting.id, d.credit) }
        assertThat(member.dissent).hasSize(1)
        assertThat(member.recordedBy).isNull()
        assertThat(asUser(reader) { minutesAccess.decision(d.meeting.id, d.credit) }.vote).isNotNull()
        // Published for E07 and E08.
        val published = checkNotNull(transactions.readInTenant(tenant.id) { decisionsApi.find(recorded.decision.id) })
        assertThat(
            published.current.conditions to published.state,
        ).isEqualTo(listOf("Security perfected before disbursement") to DecisionState.RECORDED)
        assertThat(transactions.readInTenant(tenant.id) { decisionsApi.of(d.meeting.id) }.map { it.itemId }).containsExactly(d.credit)
    }

    @Test
    fun `a recused person is never named, the counts never exceed the voters, and a recused secretary cannot record`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(CREDIT), director, secretary2) }
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1], others[2]))
        assertThat(
            rejected {
                record(d, d.credit, approvedWithConditions.copy(votesFor = 3, votesAbstain = 1, abstentions = listOf(director.id.value)))
            },
        ).containsExactly("RECUSED_NAMED")
        assertThat(rejected { record(d, d.credit, approvedWithConditions.copy(votesFor = 6)) }).containsExactly("TOO_MANY_VOTES")
        assertThat(rejected { record(d, d.credit, approvedWithConditions.copy(dissent = listOf(Dissent(others[3].id.value)))) })
            .containsExactly("NOT_PRESENT")
        assertThat(rejected { record(d, d.credit, approvedWithConditions.copy(dissent = listOf(Dissent(user.id.value)))) })
            .containsExactly("NOT_VOTING")
        assertThat(
            rejected { record(d, d.credit, approvedWithConditions.copy(votesAgainst = 0, dissent = listOf(Dissent(director2.id.value)))) },
        ).containsExactly("TOO_MANY_NAMED")
        assertThat(denied(secretary2) { record(d, d.credit, approvedWithConditions, by = secretary2) }).isEqualTo("RECUSED")
        // Nothing was stored by the refusals, the snapshots included.
        assertThat(currentVersion(d.credit)).isNull()
        assertThat(asUser(secretary) { snapshots.ofMeeting(d.meeting.id) }).isEmpty()
        // Not quorate: three present cannot decide the other item.
        val small = deciding()
        arrive(small.meeting, listOf(chair, director2, others[0]))
        assertThat(refused { record(small, small.appetite, DecisionInput(OutcomeKind.NOTED)) }).isEqualTo("NOT_QUORATE")
        assertThat(refused { record(small, small.appetite, DecisionInput(OutcomeKind.NOTED, overrideReason = "Urgent")) })
            .isEqualTo("OVERRIDE_NOT_ALLOWED")
    }

    @Test
    fun `who may record, read the history and keep notes`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val noted = DecisionInput(OutcomeKind.NOTED, text = "Noted.")
        listOf(chair, director, reader).forEach { assertThat(denied(it) { record(d, d.appetite, noted, by = it) }).isEqualTo("") }
        assertThat(denied(user) { record(d, d.appetite, noted, by = user) }).isEqualTo("404")
        record(d, d.appetite, noted)
        assertThat(asUser(chair) { minutesAccess.history(d.meeting.id, d.appetite) }.entries).hasSize(1)
        assertThat(asUser(reader) { minutesAccess.history(d.meeting.id, d.appetite) }.entries).hasSize(1)
        assertThat(denied(director) { minutesAccess.history(d.meeting.id, d.appetite) }).isEqualTo("")
        // Notes: the secretary's only; nobody else knows of them.
        listOf(
            chair,
            director,
            reader,
        ).forEach { assertThat(denied(it) { notes.read(d.meeting.id, d.appetite) ?: "none" }).isEqualTo("404") }
        // The committee's decisions to be ratified: its secretaries; members 403; someone outside 404.
        assertThat(asUser(secretary) { minutesAccess.toBeRatified(brc) }).isEmpty()
        assertThat(denied(director) { minutesAccess.toBeRatified(brc) }).isEqualTo("")
        assertThat(denied(user) { minutesAccess.toBeRatified(brc) }).isEqualTo("404")
    }

    @Test
    fun `a correction is a new entry with a reason, keeps its snapshot unless asked, and the history keeps every entry`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val first = record(d, d.appetite, DecisionInput(OutcomeKind.APPROVED, text = "Resolved: the appetite is approved."))
        val correction = DecisionInput(OutcomeKind.APPROVED, text = "Resolved: the appetite statement is approved.")
        assertThatThrownBy { record(d, d.appetite, correction, version = null) }.isInstanceOf(PreconditionRequiredException::class.java)
        assertThatThrownBy { record(d, d.appetite, correction, version = 7) }.isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(rejected { record(d, d.appetite, correction) }).containsExactly("REASON_REQUIRED")
        val second = record(d, d.appetite, correction.copy(reason = "Wording agreed with the chair"))
        assertThat(second.entry.seq to second.decision.version).isEqualTo(2 to 1L)
        assertThat(second.entry.snapshotId).describedAs("the earlier snapshot is kept").isEqualTo(first.entry.snapshotId)
        assertThat(second.entry.correction to second.entry.decidedAt).isEqualTo(true to first.entry.decidedAt)
        val third = record(d, d.appetite, correction.copy(reason = "Check the quorum again", recheckQuorum = true))
        assertThat(third.entry.snapshotId).isNotEqualTo(first.entry.snapshotId)
        val history = asUser(secretary) { minutesAccess.history(d.meeting.id, d.appetite) }
        assertThat(history.entries.map { it.seq to it.reason })
            .containsExactly(1 to null, 2 to "Wording agreed with the chair", 3 to "Check the quorum again")
        val audit = Audit.assertAudited(tenant.id, "minutes.decision.corrected", d.meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains("reasonSha256").doesNotContain("Wording agreed")
        assertThat(transactions.readInTenant(tenant.id) { decisionsApi.entry(first.decision.id, 1) }?.text).isEqualTo(first.entry.text)

        // Once released (T4 sets it), the outcome kind is fixed; the wording is not.
        transactions.inTenant(tenant.id) {
            tenantDsl.current().execute(
                "update minutes_decision set released_at = now(), released_kind = 'APPROVED', version = version + 1 where id = ?",
                first.decision.id,
            )
        }
        assertThat(refused { record(d, d.appetite, DecisionInput(OutcomeKind.NOTED, reason = "Only noted")) }).isEqualTo("RELEASED")
        val reworded = record(d, d.appetite, correction.copy(text = "Resolved: approved as amended.", reason = "Amended"))
        assertThat(reworded.entry.seq to reworded.decision.releasedAt).matches { it.first == 4 && it.second != null }
        assertThat(asUser(secretary) { minutesAccess.decision(d.meeting.id, d.appetite) }.state).isEqualTo(DecisionState.RELEASED)
    }

    @Test
    fun `the consent section is decided in one action, but for the item lifted for discussion`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val decided = asUser(secretary) { consent.decide(d.meeting.id, d.consentSection, null, false) }
        assertThat(decided.map { it.decision.itemId }).containsExactly(d.items.getValue(COMPLIANCE), d.items.getValue(TERMS))
        assertThat(decided.map { it.entry.outcomeKind }).containsExactly(OutcomeKind.NOTED, OutcomeKind.APPROVED)
        assertThat(decided.map { it.entry.consentBatchId }.distinct()).hasSize(1).doesNotContainNull()
        assertThat(decided.map { it.entry.snapshotId }.distinct()).hasSize(2)
        assertThat(decided.single { it.entry.outcomeKind == OutcomeKind.APPROVED }.entry.text).isEqualTo("Approved as presented.")
        assertThat(currentVersion(d.items.getValue(LIQUIDITY))).isNull()
        val audit = Audit.assertAudited(tenant.id, "minutes.consent.decided", d.meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains(d.items.getValue(TERMS).toString()).doesNotContain(d.items.getValue(LIQUIDITY).toString())
        // Again: nothing left to decide; another section is not a consent section.
        assertThat(refused { consent.decide(d.meeting.id, d.consentSection, null, false) }).isEqualTo("NOTHING_TO_DECIDE")
        val forDecision =
            drafts
                .let { asUser(secretary) { it.draft(d.meeting.id) } }
                .sections
                .single { it.title == "For decision" }
                .id
        assertThat(refused { consent.decide(d.meeting.id, forDecision, null, false) }).isEqualTo("WRONG_STATE")
        // The lifted item is decided on its own, and the decided ones are corrected one by one.
        record(
            d,
            d.items.getValue(LIQUIDITY),
            DecisionInput(
                OutcomeKind.DEFERRED,
                deferTo =
                    com.memo.minutes.internal
                        .DeferTarget(next = true),
            ),
        )
        val terms = d.items.getValue(TERMS)
        assertThat(
            record(d, terms, DecisionInput(OutcomeKind.APPROVED, text = "Approved as amended.", reason = "Amended")).entry.seq,
        ).isEqualTo(2)
    }

    @Test
    fun `a secretary recused from a consent item cannot decide the section`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(TERMS), secretary2) }
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        assertThat(denied(secretary2) { consent.decide(d.meeting.id, d.consentSection, null, false) }).isEqualTo("RECUSED")
        assertThat(denied(director) { consent.decide(d.meeting.id, d.consentSection, null, false) }).isEqualTo("")
    }

    @Test
    fun `notes are versions saved only when the text changed, by the secretary, with the version read`() {
        val d = deciding()
        assertThat(asUser(secretary) { notes.read(d.meeting.id, d.credit) }).isNull()
        val v1 = asUser(secretary) { notes.save(d.meeting.id, d.credit, 0, "CFO explained the security cover") }
        assertThat(v1.seq).isEqualTo(1)
        assertThat(asUser(secretary) { notes.save(d.meeting.id, d.credit, 1, "CFO explained the security cover") }.seq).isEqualTo(1)
        val v2 = asUser(secretary) { notes.save(d.meeting.id, d.credit, 1, "CFO explained the security cover; members challenged it") }
        assertThat(v2.seq).isEqualTo(2)
        assertThatThrownBy { asUser(secretary) { notes.save(d.meeting.id, d.credit, 1, "Stale") } }
            .isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(rejected { notes.save(d.meeting.id, d.credit, 2, "x".repeat(10_001)) }).containsExactly("LENGTH")
        assertThat(denied(director) { notes.save(d.meeting.id, d.credit, 2, "Mine") }).isEqualTo("404")
        val audit = Audit.assertAudited(tenant.id, "minutes.notes.saved", d.meeting.id.toString(), secretary.id.value.toString())
        assertThat(audit.canonical).contains("sha256").doesNotContain("CFO")
        val changed =
            runCatching {
                AppRoleJdbc.inTransaction(
                    tenant.id,
                ) { c -> c.update("update minutes_item_note set text = 'x' where meeting_id = ?", d.meeting.id) }
            }
        assertThat(changed.isFailure).describedAs("notes are append-only").isTrue()
        // Before the meeting starts, no notes.
        val upcoming = scheduled()
        assertThat(denied(secretary) { notes.save(upcoming.id, d.credit, 0, "x") }).isEqualTo("404")
    }

    @Test
    fun `the time defaults to the item's start, is required once held, and lies within the meeting`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        asUser(secretary) { attendance.progress(d.meeting.id, d.appetite) }
        val started = checkNotNull(transactions.readInTenant(tenant.id) { register.of(d.meeting.id) }).itemsInProgress.last()
        val recorded = record(d, d.appetite, DecisionInput(OutcomeKind.NOTED))
        assertThat(recorded.entry.decidedAt).isEqualTo(started.startedAt)
        assertThat(rejected { record(d, d.credit, DecisionInput(OutcomeKind.NOTED, decidedAt = Instant.now().plusSeconds(3600))) })
            .containsExactly("TIME_OUT_OF_RANGE")
        val held = meeting(d.meeting.id).let { asUser(secretary) { lifecycle.end(it.id, it.version) } }
        assertThat(rejected { record(d.copy(meeting = held), d.credit, DecisionInput(OutcomeKind.NOTED)) }).containsExactly("TIME_REQUIRED")
        val later = record(d.copy(meeting = held), d.credit, DecisionInput(OutcomeKind.NOTED, decidedAt = started.startedAt))
        assertThat(later.entry.seq).isEqualTo(1)
        // An item of another meeting is not found (the windows by status are DecisionStateMachineTest's).
        val upcoming = scheduled()
        assertThat(denied(secretary) { recorder.record(upcoming.id, d.credit, DecisionInput(OutcomeKind.NOTED), null) }).isEqualTo("404")
    }

    @Test
    fun `a decision under the override is to be ratified until a later quorate decision ratifies it`() {
        allowOverrides()
        val d = deciding()
        arrive(d.meeting, listOf(chair, director2, others[0]))
        val override = DecisionInput(OutcomeKind.NOTED, overrideReason = "The facility lapses tomorrow")
        val taken = record(d, d.appetite, override)
        assertThat(taken.entry.ratificationRequired).isTrue()
        assertThat(asUser(secretary) { minutesAccess.toBeRatified(brc) }.map { it.decisionId }).containsExactly(taken.decision.id)
        assertThat(asUser(secretary) { minutesAccess.decision(d.meeting.id, d.appetite) }.toBeRatified).isTrue()
        // Quorate now: the credit proposal ratifies it (APPROVED or NOTED only, quorate only).
        arrive(d.meeting, listOf(director, others[1]))
        val ratify = DecisionInput(OutcomeKind.APPROVED, text = "Resolved.", ratifiesDecisionId = taken.decision.id)
        assertThat(
            rejected {
                record(
                    d,
                    d.credit,
                    ratify.copy(
                        outcome = OutcomeKind.DEFERRED,
                        text = null,
                        deferTo =
                            com.memo.minutes.internal
                                .DeferTarget(next = true),
                    ),
                )
            },
        ).containsExactly("OUTCOME")
        assertThat(
            rejected { record(d, d.credit, ratify.copy(ratifiesDecisionId = java.util.UUID.randomUUID())) },
        ).containsExactly("unknown")
        val ratifying = record(d, d.credit, ratify)
        assertThat(ratifying.entry.ratifiesDecisionId).isEqualTo(taken.decision.id)
        assertThat(asUser(secretary) { minutesAccess.toBeRatified(brc) }).isEmpty()
        assertThat(asUser(secretary) { minutesAccess.decision(d.meeting.id, d.appetite) }.toBeRatified).isFalse()
        // A second ratification of the same decision is refused.
        val again = DecisionInput(OutcomeKind.NOTED, ratifiesDecisionId = taken.decision.id)
        assertThat(rejected { record(d, d.items.getValue(COMPLIANCE), again) }).containsExactly("unknown")
    }

    @Test
    fun `entries never change, a decision keeps its item, and nothing is deleted`() {
        val d = deciding()
        arrive(d.meeting, listOf(chair, director, director2, others[0], others[1]))
        val recorded = record(d, d.appetite, DecisionInput(OutcomeKind.NOTED))
        listOf(
            "update minutes_decision_entry set outcome_kind = 'REJECTED' where decision_id = ?",
            "delete from minutes_decision_entry where decision_id = ?",
            "delete from minutes_decision where id = ?",
            "update minutes_decision set item_id = gen_random_uuid(), version = version + 1 where id = ?",
            "update minutes_decision set current_seq = 5, version = version + 1 where id = ?",
            "update minutes_decision set version = version + 2 where id = ?",
        ).forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update(sql, recorded.decision.id) } }
            assertThat(changed.exceptionOrNull()).describedAs(sql).isNotNull()
        }
    }
}
