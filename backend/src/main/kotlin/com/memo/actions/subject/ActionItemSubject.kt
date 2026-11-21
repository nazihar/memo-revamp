package com.memo.actions.subject

import com.memo.forum.ActionsPolicy
import com.memo.workflow.BlockedReason
import com.memo.workflow.DefinitionProblem
import com.memo.workflow.SeededDefinition
import com.memo.workflow.SubjectDefinition
import com.memo.workflow.WorkflowSubject
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Action items as the workflow engine sees them (ADR-0067, ADR-0068, design E07 §1.8). Phase 4 E07 T2 registers what a
 * definition bound to `action-item` needs to be checked and seeded: the slots it may name, the subject's own rules for
 * such definitions, the initiator's part, and the built-in definition every tenant gets. There are no action items yet:
 * T3 adds them, with who each slot names, the due time, the attributes, eligibility, the snapshot and the lock.
 */
@Component
class ActionItemSubject : WorkflowSubject {
    override val type = ActionsPolicy.SUBJECT

    override val slots = setOf(OWNER, VERIFIER, CHAIR, SECRETARIES)

    /** The secretary who issues an action may own it, or verify others' (ADR-0068): independence is the verifier slot's. */
    override val initiatorTakesPart = true

    override val seededDefinitions = listOf(SeededDefinition(DEFINITION_KEY, DEFINITION_NAME, DEFINITION_DESCRIPTION, DEFINITION_BODY))

    override fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason> = emptyMap()

    override fun snapshot(subjectId: UUID): Map<String, Any?> = emptyMap()

    override fun checkDefinition(definition: SubjectDefinition): List<DefinitionProblem> = ActionDefinitionRules.problems(definition)

    companion object {
        const val OWNER = "owner"
        const val VERIFIER = "verifier"
        const val CHAIR = "chair"
        const val SECRETARIES = "secretaries"

        const val DEFINITION_KEY = ActionsPolicy.DEFAULT_DEFINITION
        const val DEFINITION_NAME = "Action items"
        const val DEFINITION_DESCRIPTION =
            "Built in: the owner's step, due on the action's date with reminders and escalation, then verification by someone else."

        /**
         * The built-in definition (design E07 §1.8): the owner's step is due at the end of the action's date, with a reminder
         * 5 working days before and 5 after, and a notice to the holder's supervisor by the reporting line after 10 (the
         * holder keeps the action). Then verification by the verifier slot (never the owner, holder or a contributor):
         * APPROVE closes, SEND_BACK returns it to the owner with a remark, a reminder after 3 working days and the chair told
         * after 10.
         */
        val DEFINITION_BODY =
            """
            {"schemaVersion":2,"subject":"action-item","start":"owner","steps":[
              {"id":"owner","type":"review","name":"Owner",
               "assignee":{"kind":"subject","slot":"owner"},"actions":["RECOMMEND"],
               "sla":{"from":"subject","remind":{"before":"P5D","after":["P5D"]},
                      "escalate":{"after":"P10D","action":"notify"}},
               "next":"verify"},
              {"id":"verify","type":"approve","name":"Verification",
               "assignee":{"kind":"subject","slot":"verifier"},"actions":["APPROVE","SEND_BACK"],
               "remarks":["SEND_BACK"],
               "sla":{"due":"P5D","remind":{"after":["P3D"]},
                      "escalate":{"after":"P10D","action":"notify","to":{"kind":"subject","slot":"chair"}}},
               "next":"end"}]}
            """.trimIndent()
    }
}

/**
 * What a definition for action items must keep (design E07 §1.8), beside the engine's own checks: the action starts with
 * its owner, due on the action's date; it is sent back, never rejected or returned; its closure is always verified by a
 * later step; and the owner cannot hand it on (forward or delegate), since the owner is who the committee named.
 */
object ActionDefinitionRules {
    const val START_WITH_OWNER = "ACTION_START_WITH_OWNER"
    const val NOT_REJECTED = "ACTION_NOT_REJECTED"
    const val VERIFIED = "ACTION_VERIFIED"
    const val OWNER_KEEPS = "ACTION_OWNER_KEEPS"

    private val REFUSED = setOf("REJECT", "RETURN")
    private val HANDS_ON = setOf("FORWARD", "DELEGATE")

    fun problems(definition: SubjectDefinition): List<DefinitionProblem> {
        val start = definition.step(definition.start)
        val at = definition.pointer(definition.start)
        val problems = mutableListOf<DefinitionProblem>()
        val ownerFirst = start?.let { it.human && it.slot == ActionItemSubject.OWNER && it.dueFromSubject } == true
        if (!ownerFirst) {
            problems +=
                DefinitionProblem(
                    at,
                    START_WITH_OWNER,
                    "the first step goes to the action's owner (the owner slot) and is due on the action's date (sla.from: subject)",
                )
        }
        definition.steps.filter { step -> step.actions.any { it in REFUSED } }.forEach { step ->
            problems +=
                DefinitionProblem(
                    "${definition.pointer(step.id)}/actions",
                    NOT_REJECTED,
                    "an action is sent back, never rejected or returned",
                )
        }
        if (start != null && start.actions.any { it in HANDS_ON }) {
            problems += DefinitionProblem("$at/actions", OWNER_KEEPS, "the owner cannot forward or delegate the action")
        }
        if (start != null && unverified(definition, start.targets)) {
            problems += DefinitionProblem("/steps", VERIFIED, "every way from the owner's step to the end passes a verification step")
        }
        return problems
    }

    /**
     * Whether some way from [from] reaches the end without a human step other than the owner's (the start). Coming back to
     * the start is not a way to the end; an unknown step is the engine's problem, not this rule's.
     */
    private fun unverified(
        definition: SubjectDefinition,
        from: List<String>,
    ): Boolean {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(from)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id == END) return true
            definition
                .step(id)
                ?.takeIf { id != definition.start && !it.human && seen.add(id) }
                ?.let { pending.addAll(it.targets) }
        }
        return false
    }

    private const val END = "end"
}
