package com.memo.workflow.internal

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.RouteEntry
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.Definition
import com.memo.workflow.engine.Approve
import com.memo.workflow.engine.Decision
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.Transition
import com.memo.workflow.engine.TransitionContext
import org.springframework.stereotype.Component
import java.util.UUID

/** What happened at a step in a simulation (design E06 §4). */
enum class SimulatedOutcome { DECIDED, BLOCKED, NOTIFIED, BRANCHED, PASSED }

/** How a simulation ended: approved, stopped at a task nobody can hold, or at the command limit. */
enum class SimulationEnd { APPROVED, BLOCKED, LIMIT }

/** A person a simulated step reaches; [actingFor] when they would hold the task as someone's substitute. */
data class SimulatedPerson(
    val id: UUID,
    val actingFor: UUID? = null,
)

/**
 * One step reached, in order: its definition [nodeId]; the people it would reach (a notify step's recipients); why it
 * would be blocked; for a branch, the condition that matched ([branch], null for `otherwise`) and where it led; for a
 * DoA step, the approval [group].
 */
data class SimulatedStep(
    val nodeId: String,
    val outcome: SimulatedOutcome,
    val people: List<SimulatedPerson> = emptyList(),
    val blockedReason: BlockedReason? = null,
    val branch: Int? = null,
    val next: String? = null,
    val group: Int? = null,
)

data class Simulation(
    val steps: List<SimulatedStep>,
    val ended: SimulationEnd,
)

/**
 * Runs a compiled definition in memory against sample attribute values (design E06 §4): the pure engine with the
 * organisation's resolvers, substitutes, settings and DoA as they are now, and eligibility from the sample. Every open
 * decision gets the step's positive action from its holder, oldest first, until the instance ends, a task is blocked,
 * or [MAX_COMMANDS]. Writes nothing. Inside a read-only tenant transaction.
 */
@Component
class DefinitionSimulator(
    private val settings: WorkflowSettings,
    private val subjects: List<WorkflowSubject>,
    private val resolvers: OrgResolvers,
    private val substitutes: SubstitutesInForce,
    private val doa: DoaMatcher,
    private val orgLimits: ForwardLimits,
) {
    fun simulate(
        definition: Definition,
        subjectType: String,
        initiator: UUID,
        attributes: Map<String, Any?>,
        route: List<RouteEntry>,
    ): Simulation {
        val transition = Transition(context(subjectType, initiator, attributes), definition)
        val trace = Trace(definition)
        var state = accepted(transition.start(initiator, route.flatMap { it.people }, null, route)).also(trace::record).state
        var commands = 1
        while (state.status == InstanceStatus.RUNNING && commands < MAX_COMMANDS) {
            val outcome = decideNext(transition, state) ?: break
            state = outcome.also(trace::record).state
            commands++
        }
        val ended =
            when {
                state.status == InstanceStatus.APPROVED -> SimulationEnd.APPROVED
                commands >= MAX_COMMANDS -> SimulationEnd.LIMIT
                else -> SimulationEnd.BLOCKED
            }
        return Simulation(trace.steps(), ended)
    }

    /** The oldest open decision someone can take, decided positively by its holder; null when none is left or it is refused. */
    private fun decideNext(
        transition: Transition,
        state: InstanceState,
    ): Outcome? {
        val task = state.openDecisions.firstOrNull { it.assignee != null && it.blocked == null && !it.waiting }
        val decision = task?.let { transition.apply(state, Approve(checkNotNull(it.assignee), it.id)) }
        return (decision as? Decision.Accepted)?.outcome
    }

    private fun context(
        subjectType: String,
        initiator: UUID,
        attributes: Map<String, Any?>,
    ): TransitionContext {
        val subject = subjects.singleOrNull { it.type == subjectType } ?: throw WorkflowRefusedException(UNKNOWN_SUBJECT_TYPE)
        val ineligible = mutableMapOf<UUID, BlockedReason?>()
        val authority = lazy { doa.matchAt(subjectType, attributes, initiator) }
        var next = 0L
        return TransitionContext(
            newId = { UUID(SIMULATION_IDS, ++next) },
            eligibility = { person -> ineligible.getOrPut(person) { subject.ineligibleAt(attributes, listOf(person))[person] } },
            maxApprovers = settings.maxApprovers(),
            attributes = { attributes },
            resolver = resolvers.resolver(),
            oncePerRound = settings.oncePerRound(),
            // SOD-3 as in the runner; a sample item has no interests.
            substituteOf = { person -> substitutes.of(person)?.takeUnless { it == initiator } },
            doa = authority::value,
            within = orgLimits::within,
        )
    }

    /** The steps reached, keyed by the engine's step id (a DoA group is a step of its own), in the order reached. */
    private class Trace(
        private val definition: Definition,
    ) {
        private val reached = linkedMapOf<Any, SimulatedStep>()

        fun record(outcome: Outcome) {
            outcome.followUps.forEach { record ->
                val node = record.details["step"] as? String ?: return@forEach
                when (record.kind) {
                    ActionKind.ROUTE -> reached[record] = branched(node, record.details["taken"] as? Int)
                    ActionKind.NOTIFY -> reached[record] = notified(node, outcome)
                    else -> reached[record] = SimulatedStep(node, SimulatedOutcome.PASSED)
                }
            }
            outcome.effects.filterIsInstance<Effect.TaskCreated>().forEach { created -> task(outcome.state, created) }
        }

        fun steps(): List<SimulatedStep> = reached.values.toList()

        private fun branched(
            node: String,
            taken: Int?,
        ): SimulatedStep {
            val step = definition.step(node)
            val next = taken?.let { step.branches[it].next } ?: step.otherwise
            return SimulatedStep(node, SimulatedOutcome.BRANCHED, branch = taken, next = next)
        }

        private fun notified(
            node: String,
            outcome: Outcome,
        ): SimulatedStep {
            val notify = outcome.effects.filterIsInstance<Effect.Notify>().firstOrNull { it.nodeId == node }
            return SimulatedStep(node, SimulatedOutcome.NOTIFIED, notify?.recipients.orEmpty().map { SimulatedPerson(it) })
        }

        private fun task(
            state: InstanceState,
            created: Effect.TaskCreated,
        ) {
            val step = state.round.steps.firstOrNull { it.id == created.stepId } ?: return
            val task = created.task
            val before = reached[step.id] ?: SimulatedStep(step.nodeId, SimulatedOutcome.DECIDED, group = step.doaGroup)
            val assignee = task.assignee
            reached[step.id] =
                if (task.blocked != null || assignee == null) {
                    before.copy(outcome = SimulatedOutcome.BLOCKED, blockedReason = task.blocked ?: before.blockedReason)
                } else {
                    before.copy(people = before.people + SimulatedPerson(assignee, task.onBehalfOf))
                }
        }
    }

    private fun accepted(decision: Decision): Outcome =
        when (decision) {
            is Decision.Accepted -> decision.outcome
            is Decision.Refused -> throw WorkflowRefusedException(decision.reason.name)
        }

    companion object {
        /** Commands one simulation may apply (threat model D2). */
        const val MAX_COMMANDS = 500
        const val UNKNOWN_SUBJECT_TYPE = "UNKNOWN_SUBJECT_TYPE"

        /** The high half of every id a simulation makes up, so they can never be mistaken for stored rows. */
        private const val SIMULATION_IDS = 0x51D0_0000_0000_0000L
    }
}
