package com.memo.workflow.internal

import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Attribute
import com.memo.workflow.definition.ConditionOutcome
import com.memo.workflow.definition.Conditions
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.DoaGroup
import com.memo.workflow.engine.DoaMatch
import org.springframework.stereotype.Component
import java.util.UUID

/** DoA members as the engine's assignees (design E04 §3): a position's holder, or a role's holders in a unit. */
object DoaMembers {
    const val INITIATOR = "initiator"

    /** The assignee [member] stands for; throws when it is neither a position nor a role in a unit. */
    fun assignee(member: DoaMember): Assignee =
        when (member.kind) {
            "position" -> {
                require(member.roleId == null && member.unit == null)
                Assignee(AssigneeKind.POSITION, positionId = checkNotNull(member.positionId))
            }

            "role" -> {
                require(member.positionId == null)
                val unit = checkNotNull(member.unit)
                Assignee(
                    AssigneeKind.ROLE,
                    roleId = checkNotNull(member.roleId),
                    unit =
                        if (unit ==
                            INITIATOR
                        ) {
                            null
                        } else {
                            UUID.fromString(unit)
                        },
                )
            }

            else -> {
                throw IllegalArgumentException("unknown member kind")
            }
        }
}

/** What a simulation found (design E04 §4.3): the version and rule, and who each group would go to. */
data class DoaSimulation(
    val version: Int?,
    val rule: Int?,
    val ruleName: String?,
    /** Why nothing matched: `NO_DOA` or `NO_RULE`. */
    val reason: String?,
    val groups: List<Pair<Int, List<UUID>>>,
)

/**
 * Matches an item against a DoA version (design E04 §4.3): the first rule, in order, for the subject type and memo type,
 * whose unit is the initiator's unit (or above it, with its subtree) and whose condition holds for the item's
 * attributes. Its groups go to the engine; what matched is recorded on each step. Inside a tenant transaction.
 */
@Component
class DoaMatcher(
    private val versions: DoaVersions,
    private val directory: OrgDirectory,
    private val resolvers: OrgResolvers,
    private val subjects: List<WorkflowSubject>,
) {
    /** The version a new instance of [definition] pins: the one in force today, when the definition has a `doa` step. */
    fun pin(definition: Definition): UUID? =
        if (definition.steps.any { it.type == StepType.DOA }) versions.inForce(directory.today())?.id else null

    /** The DoA for an instance pinned to [versionId] (none when null). */
    fun match(
        versionId: UUID?,
        subject: WorkflowSubject,
        subjectId: UUID,
        initiator: UUID,
    ): DoaMatch {
        val version = versionId?.let(versions::byId) ?: return DoaMatch.NONE
        return matched(version, subject.type, subject.attributeValues(subjectId), initiator).first
    }

    /** Who would approve an item of [subjectType] with [attributes], started by [initiator], under version [number] or the one in force. */
    fun simulate(
        number: Int?,
        subjectType: String,
        attributes: Map<String, Any?>,
        initiator: UUID,
    ): DoaSimulation {
        val version = (if (number == null) versions.inForce(directory.today()) else versions.version(number))
        if (number != null && version == null) throw DoaNotFoundException()
        if (version == null) return DoaSimulation(null, null, null, DoaMatch.NO_DOA, emptyList())
        val (match, rule) = matched(version, subjectType, attributes, initiator)
        val resolver = resolvers.resolver()
        val groups =
            match.groups.map { group ->
                group.needed to
                    group.members
                        .flatMap { resolver.resolve(it, initiator).people }
                        .distinct()
                        .filter { it != initiator }
            }
        val reason = if (rule == null) DoaMatch.NO_RULE else null
        return DoaSimulation(version.number, rule?.first, rule?.second?.name, reason, groups)
    }

    private fun matched(
        version: DoaVersionRow,
        subjectType: String,
        attributes: Map<String, Any?>,
        initiator: UUID,
    ): Pair<DoaMatch, Pair<Int, DoaRule>?> {
        val declared =
            subjects
                .firstOrNull { it.type == subjectType }
                ?.attributes
                ?.map { Attribute(it.key, it.value) }
                .orEmpty()
        val unit = directory.positionsOf(listOf(initiator))[initiator]?.unitId
        val above = unit?.let(directory::ancestors).orEmpty()
        val rules = versions.rules(version.id)
        val found =
            rules.withIndex().firstOrNull { (_, rule) ->
                rule.subjectType == subjectType &&
                    rule.memoTypeId.let { it == null || it.toString() == attributes[MEMO_TYPE]?.toString() } &&
                    (rule.unitId == null || rule.unitId == unit || (rule.includeSubtree && rule.unitId in above)) &&
                    holds(rule.condition, declared, attributes)
            }
        val base = mapOf("doa" to mapOf("version" to version.number, "versionId" to version.id.toString()))
        if (found == null) return DoaMatch(emptyList(), base + ("reason" to DoaMatch.NO_RULE)) to null
        val (index, rule) = found
        val groups = rule.groups.map { group -> DoaGroup(group.needed, group.of.map(DoaMembers::assignee)) }
        val record =
            mapOf(
                "doa" to mapOf("version" to version.number, "versionId" to version.id.toString(), "rule" to index, "ruleName" to rule.name),
            )
        return DoaMatch(groups, record) to (index to rule)
    }

    /** A rule's condition: none holds always; one that does not compile or cannot be evaluated does not hold. */
    private fun holds(
        condition: String?,
        declared: List<Attribute>,
        attributes: Map<String, Any?>,
    ): Boolean =
        condition == null ||
            Conditions.compile(condition, declared).first?.let { (it.evaluate(attributes) as? ConditionOutcome.Value)?.result } == true

    private companion object {
        const val MEMO_TYPE = "memoTypeId"
    }
}
