package com.memo.actions.subject

import com.memo.actions.internal.ActionReferences
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.DecisionSource
import com.memo.common.authz.Clearances
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumPolicies
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import com.memo.workflow.AttributeType
import com.memo.workflow.BlockedReason
import com.memo.workflow.DefinitionProblem
import com.memo.workflow.SeededDefinition
import com.memo.workflow.SlotHolder
import com.memo.workflow.SubjectDefinition
import com.memo.workflow.SubjectSummary
import com.memo.workflow.WorkflowSubject
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Action items as the workflow engine sees them (ADR-0067, ADR-0068, design E07 §1.8): the slots a definition bound to
 * `action-item` may name and who each names on an action now, its due time, the attributes its conditions read, who may
 * not hold its tasks, the people with an interest, how lists show it, the lock, the subject's own rules for its
 * definitions, the initiator's part and the built-in definition every tenant gets. Every call runs in the engine's tenant
 * transaction; none needs a signed-in user (timers and staff changes call it too).
 * Who each slot names is [ActionSlots].
 */
@Component
class ActionItemSubject(
    private val store: ActionStore,
    private val slotHolders: ActionSlots,
    private val policies: ForumPolicies,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val org: OrgDirectory,
    private val decisions: DecisionSource,
) : WorkflowSubject {
    override val type = ActionsPolicy.SUBJECT

    override val slots = SLOTS

    /** The secretary who issues an action may own it, or verify others' (ADR-0068): independence is the verifier slot's. */
    override val initiatorTakesPart = true

    override val seededDefinitions = listOf(SeededDefinition(DEFINITION_KEY, DEFINITION_NAME, DEFINITION_DESCRIPTION, DEFINITION_BODY))

    override fun slot(
        subjectId: UUID,
        slot: String,
    ): List<SlotHolder> {
        val row = store.find(subjectId) ?: return emptyList()
        return slotHolders.of(row, slot)
    }

    /** The owner's step is due at the end of the action's date in the tenant's zone; null for a draft without one. */
    override fun dueAt(
        subjectId: UUID,
        nodeId: String,
    ): Instant? = store.find(subjectId)?.dueDate?.let { ActionReferences.dueAt(it, org.zone()) }

    /** Active and cleared for the action's level, or not (the engine creates the task blocked). */
    override fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason> {
        val row = store.find(subjectId) ?: return emptyMap()
        val rank = clearances.level(row.classificationId)?.rank ?: Int.MAX_VALUE
        val people = this.users.of(users.toSet())
        val ranks = clearances.rankOf(users.toSet())
        return users
            .mapNotNull { user ->
                when {
                    people[user]?.status != UserStatus.ACTIVE -> user to BlockedReason.INACTIVE
                    (ranks[user] ?: 0) < rank -> user to BlockedReason.NO_CLEARANCE
                    else -> null
                }
            }.toMap()
    }

    /**
     * Those recused from the action's item are never candidates for its steps (SOD-4), but the owner: a person recused
     * from the item may own an action on it (design E07 §1.3).
     */
    override fun interested(subjectId: UUID): Set<UUID> {
        val row = store.find(subjectId) ?: return emptySet()
        return slotHolders.interested(row)
    }

    override val attributes: Map<String, AttributeType> = ATTRIBUTES

    override fun attributeValues(subjectId: UUID): Map<String, Any?> {
        val row = store.find(subjectId) ?: return emptyMap()
        return mapOf(
            "priority" to row.priority.name,
            "fromResolution" to (row.decisionId?.let { decisions.find(it)?.resolution } ?: false),
            "source" to row.source.name,
            "committeeType" to policies.current(row.forumId)?.name,
            "dueRevisions" to row.dueRevisions,
            "reopened" to (row.reopenCount > 0),
        )
    }

    /** The reference (null for a draft), the title and the level's rank, which decides whether a reader sees the title. */
    override fun summaries(subjectIds: Collection<UUID>): Map<UUID, SubjectSummary> {
        if (subjectIds.isEmpty()) return emptyMap()
        val ranks = clearances.levels().associate { it.id to it.rank }
        return subjectIds
            .mapNotNull { store.find(it) }
            .associate { it.id to SubjectSummary(it.reference, it.title, ranks[it.classificationId] ?: Int.MAX_VALUE) }
    }

    /** The action row, so every change of an action and every engine action on it are serialised in one order. */
    override fun lock(subjectId: UUID) {
        store.lock(subjectId)
    }

    /** What an engine action applies to: the reference, the due date and the action's version (ids and dates only). */
    override fun snapshot(subjectId: UUID): Map<String, Any?> {
        val row = store.find(subjectId) ?: return emptyMap()
        return mapOf("reference" to row.reference, "dueDate" to row.dueDate?.toString(), "version" to row.version)
    }

    override fun checkDefinition(definition: SubjectDefinition): List<DefinitionProblem> = ActionDefinitionRules.problems(definition)

    companion object {
        const val OWNER = "owner"
        const val VERIFIER = "verifier"
        const val CHAIR = "chair"
        const val SECRETARIES = "secretaries"
        val SLOTS = setOf(OWNER, VERIFIER, CHAIR, SECRETARIES)

        /** What a definition's conditions read as `item.<name>` (design E07 §1.8). */
        val ATTRIBUTES: Map<String, AttributeType> =
            mapOf(
                "priority" to AttributeType.STRING,
                "fromResolution" to AttributeType.BOOL,
                "source" to AttributeType.STRING,
                "committeeType" to AttributeType.STRING,
                "dueRevisions" to AttributeType.INT,
                "reopened" to AttributeType.BOOL,
            )

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
