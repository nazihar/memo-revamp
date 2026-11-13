package com.memo.workflow.internal

import com.memo.common.authz.RoleHolders
import com.memo.common.web.FieldProblem
import com.memo.identity.UserSummaries
import com.memo.org.OrgDirectory
import com.memo.org.OrgNames
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** What the definition endpoints answer: summaries with people's names, a version with its body and problems. */
@Component
class DefinitionResponses(
    private val reads: DefinitionReads,
    private val users: UserSummaries,
    private val directory: OrgDirectory,
    private val names: OrgNames,
    private val roles: RoleHolders,
    private val json: JsonMapper,
) {
    fun summaries(definitions: List<Pair<DefinitionRow, List<VersionRow>>>): List<DefinitionSummary> {
        val people = people(definitions.flatMap { it.second })
        return definitions.map { (definition, versions) ->
            DefinitionSummary(
                key = definition.key,
                name = definition.name,
                description = definition.description,
                fragment = definition.fragment,
                builtIn = definition.builtIn,
                publishedVersion = versions.firstOrNull { it.state == VersionState.PUBLISHED }?.number,
                openVersion =
                    versions
                        .firstOrNull { it.state == VersionState.DRAFT || it.state == VersionState.IN_REVIEW }
                        ?.summary(people),
            )
        }
    }

    fun definition(key: String): DefinitionResponse {
        val (definition, versions) = reads.definition(key)
        val people = people(versions)
        return DefinitionResponse(summaries(listOf(definition to versions)).single(), versions.map { it.summary(people) })
    }

    fun version(loaded: LoadedVersion): VersionResponse {
        @Suppress("UNCHECKED_CAST")
        val body = json.convertValue(reads.body(loaded.version), Map::class.java) as Map<String, Any?>
        return VersionResponse(
            id = loaded.version.id,
            key = loaded.definition.key,
            version = loaded.version.summary(people(listOf(loaded.version))),
            revision = loaded.version.revision,
            body = body,
            problems = reads.problems(loaded.version),
            labels = labels(reads.body(loaded.version)),
        )
    }

    /**
     * Names for the people, positions, units and roles a body mentions (design E06 §5), so the builder and the diff show
     * names without a request per id. Ids that no longer exist are left out.
     */
    fun labels(body: JsonNode): Map<UUID, String> {
        val found = mutableMapOf<String, MutableSet<UUID>>()

        fun walk(node: JsonNode) {
            node.properties().forEach { (name, value) ->
                if (name in LABELLED && value.isString) {
                    runCatching { UUID.fromString(value.asString()) }.getOrNull()?.let { found.getOrPut(name) { mutableSetOf() } += it }
                }
                walk(value)
            }
            if (node.isArray) node.forEach(::walk)
        }
        walk(body)
        val people = found[USER].orEmpty()
        val roleIds = found[ROLE].orEmpty()
        return users.of(people).mapValues { it.value.displayName } +
            directory.positions(found[POSITION].orEmpty()).mapValues { it.value.title } +
            names.units(found[UNIT].orEmpty()) +
            (if (roleIds.isEmpty()) emptyMap() else roles.names().filterKeys { it in roleIds })
    }

    /** A request body as JSON, refused when it could not be stored (1 MiB, as the column allows). */
    fun tree(body: Map<String, Any?>): JsonNode {
        val node: JsonNode = json.valueToTree(body)
        if (json.writeValueAsBytes(node).size > MAX_BODY_BYTES) {
            throw DefinitionInvalidException(listOf(FieldProblem("", "TOO_LARGE", "A definition is at most 1 MiB.")))
        }
        return node
    }

    private fun people(versions: List<VersionRow>): Map<UUID, Person> {
        val ids = versions.flatMap { listOfNotNull(it.submittedBy, it.reviewedBy) }.toSet()
        return users.of(ids).mapValues { (id, summary) -> Person(id, summary.displayName) }
    }

    private fun VersionRow.summary(people: Map<UUID, Person>) =
        VersionSummary(
            number = number,
            state = state,
            submittedBy = submittedBy?.let { people[it] ?: Person(it, "") },
            submittedAt = submittedAt,
            reviewedBy = reviewedBy?.let { people[it] ?: Person(it, "") },
            reviewedAt = reviewedAt,
            reviewRemark = reviewRemark,
            publishedAt = publishedAt,
        )

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576
        const val USER = "userId"
        const val POSITION = "positionId"
        const val UNIT = "unit"
        const val ROLE = "roleId"
        val LABELLED = setOf(USER, POSITION, UNIT, ROLE)
    }
}

/** The version number of a path; anything else names no version (404, after the access checks). */
internal fun versionNumber(path: String): Int = path.toIntOrNull() ?: throw DefinitionNotFoundException()
