package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.refuseIf
import com.memo.workflow.SubjectRef
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.engine.Migrations
import com.memo.workflow.engine.StepState
import org.springframework.stereotype.Component
import java.util.UUID

/** One instance as a migration sees it: where it is, and whether it can move to the target version (and why not). */
data class MigrationCandidate(
    val instanceId: UUID,
    val subject: SubjectRef,
    /** The version it is on; null when that is not a version of this definition. */
    val fromVersion: Int?,
    /** Already on the target version. */
    val onTarget: Boolean,
    /** Why it cannot move; null when it can (or is already there). */
    val reason: String?,
    /** The steps it is at now, by name. */
    val openSteps: List<String>,
)

/** The published version a migration moves instances to, and every version of the definition by id. */
private data class Target(
    val definitionId: UUID,
    val versionId: UUID,
    val number: Int,
    val numbers: Map<UUID, Int>,
)

/**
 * Moving running instances to the published version of their definition (design E03 §4.7, §5, §11): a preview lists
 * the instances on other versions with what would stop each one, and a migration moves the listed ones (at most
 * [MAX_BATCH], one transaction), each through the engine with a `MIGRATE` action and an audit entry. Inside a tenant
 * transaction; the caller has checked `workflow.definitions.approve` (and a step-up for the migration).
 */
@Component
class InstanceMigrations(
    private val versions: DefinitionVersions,
    private val instances: InstanceStore,
    private val compiled: CompiledDefinitions,
    private val runner: WorkflowRunner,
) {
    /** The target version's number and the candidates: [instanceIds], or every instance on another version. */
    fun preview(
        key: String,
        instanceIds: List<UUID>?,
    ): Pair<Int, List<MigrationCandidate>> {
        val target = target(key)
        val ids =
            instanceIds?.distinct()
                ?: instances.onOtherVersions(target.definitionId, target.versionId, PREVIEW_LIMIT).map { it.first }
        return target.number to ids.mapNotNull { candidate(target, it) }
    }

    /** Moves each of [instanceIds] that can move; [preview] then shows every one of them as it is afterwards. */
    fun migrate(
        key: String,
        instanceIds: List<UUID>,
    ) {
        require(instanceIds.size in 1..MAX_BATCH) { "List 1 to $MAX_BATCH instances." }
        val target = target(key)
        val actor = checkNotNull(ActorContext.current().actor as? Actor.User) { "migrations are run by signed-in users" }
        instanceIds.distinct().forEach { id ->
            val candidate = candidate(target, id)
            if (candidate != null && candidate.reason == null && !candidate.onTarget) {
                val from = checkNotNull(candidate.fromVersion)
                val pinned =
                    target.numbers.entries
                        .first { it.value == from }
                        .key
                try {
                    runner.migrate(id, actor, pinned to from, target.versionId to target.number)
                } catch (_: WorkflowRefusedException) {
                    // Changed since it was read (the lock decides); the answer below says why it stayed.
                }
            }
        }
    }

    private fun target(key: String): Target {
        val definition = versions.definition(key) ?: throw DefinitionNotFoundException()
        val all = versions.versions(listOf(definition.id))
        val published = all.firstOrNull { it.state == VersionState.PUBLISHED }
        refuseIf(published == null, "NOT_PUBLISHED") { "The definition has no published version to migrate to." }
        return Target(definition.id, published!!.id, published.number, all.associate { it.id to it.number })
    }

    private fun candidate(
        target: Target,
        instanceId: UUID,
    ): MigrationCandidate? {
        val loaded = instances.load(instanceId, lock = false) ?: return null
        val pinned = loaded.definitionVersionId
        val from = target.numbers[pinned]
        val onTarget = pinned == target.versionId
        val reason =
            when {
                onTarget -> null
                from == null -> OTHER_DEFINITION
                from > target.number -> NOT_OLDER
                else -> Migrations.refusal(loaded.state, compiled.get(target.versionId))?.name
            }
        val open =
            loaded.state.round.steps
                .filter { it.state == StepState.ACTIVE }
                .map { it.name ?: it.nodeId }
        return MigrationCandidate(instanceId, SubjectRef(loaded.subjectType, loaded.subjectId), from, onTarget, reason, open)
    }

    companion object {
        const val MAX_BATCH = 100
        const val PREVIEW_LIMIT = 500
        const val OTHER_DEFINITION = "OTHER_DEFINITION"
        const val NOT_OLDER = "NOT_OLDER"
    }
}
