package com.memo.search

import java.sql.Connection
import java.util.UUID

/**
 * The register's plan gate (ADR-0069, ADR-0054; design E08 §8): the statement `search_find_decisions` and
 * `search_decision_stats` run (`EXECUTE … USING`, planned per call), prepared as the schema owner the definer functions
 * run as and `EXPLAIN`ed with a reader profile's parameters, so a sequential scan of `search_decision` is seen. Used by
 * [DecisionSearchExplainIT] (rows written straight into the table) and [RegisterVolumeIT] (the committee volume with the
 * profiles' real parameters).
 */
object RegisterPlans {
    /** A reader profile's parameters: oversight of all, officer and member committees, `forum.read`, recused items. */
    data class Profile(
        val all: Boolean,
        val officers: List<UUID>,
        val members: List<UUID>,
        val reader: Boolean,
        val recused: List<UUID> = emptyList(),
        val clearance: Int = 2,
        val viewer: UUID = UUID.randomUUID(),
    ) {
        val inScope: List<UUID> get() = officers + members
    }

    /** One search shape: words or a number given, the order, the committee filter and the tsquery / number keys. */
    data class Shape(
        val name: String,
        val text: Boolean,
        val sort: String,
        val forums: (Profile) -> List<UUID>?,
        val tsquery: String = "null",
        val refNorm: String = "null",
        val refTail: String = "null",
    )

    /**
     * The shapes of design E08 §8's review: the three orders without words, one committee and number order (on [first]),
     * rare and common words, a resolution number in full and by its tail, and a memo number.
     */
    fun shapes(
        rare: String,
        common: String,
        number: String,
        tail: String,
        memo: String,
        first: (Profile) -> UUID,
    ): List<Shape> =
        listOf(
            Shape("newest", false, "NEWEST", { null }),
            Shape("oldest", false, "OLDEST", { null }),
            Shape("one committee", false, "NEWEST", { p -> listOf(first(p)) }),
            Shape("number order", false, "NUMBER", { p -> listOf(first(p)) }),
            Shape("text", true, "RELEVANCE", { null }, tsquery = "websearch_to_tsquery('english', '$rare')"),
            Shape("text, newest", true, "NEWEST", { null }, tsquery = "websearch_to_tsquery('english', '$common')"),
            Shape("number", true, "RELEVANCE", { null }, refNorm = "'$number'"),
            Shape("number tail", true, "RELEVANCE", { null }, refTail = "'$tail'"),
            Shape("memo number", true, "RELEVANCE", { null }, refNorm = "'$memo'"),
        )

    /**
     * Each of [shapes] prepared on [c] (an owner's transaction bound to the tenant) and explained for every one of
     * [profiles]; answers the sequential scans of `search_decision` found, and prints the analysed plan of [shown]'s.
     */
    fun searchScans(
        c: Connection,
        tenant: UUID,
        profiles: Map<String, Profile>,
        shapes: List<Shape>,
        shown: String,
        label: String,
    ): List<String> {
        c.exec("set plan_cache_mode = force_custom_plan")
        return shapes.flatMap { shape ->
            val statement = "s_" + shape.name.replace(Regex("[^a-z]"), "_")
            val sql = c.lines("select search_find_decisions_sql(${shape.text}, '${shape.sort}')").single()
            c.exec("prepare $statement(${TYPES.joinToString()}) as $sql")
            val scans =
                profiles.mapNotNull { (name, profile) ->
                    val plan = c.lines("explain execute $statement(${arguments(tenant, profile, shape)})")
                    plan.takeIf { lines -> lines.any { it.contains("Seq Scan on search_decision") } }?.let {
                        "$name, ${shape.name}:\n${it.joinToString("\n")}"
                    }
                }
            val analysed = c.lines("explain (analyze, buffers) execute $statement(${arguments(tenant, profiles.getValue(shown), shape)})")
            println("$label plan, $shown, ${shape.name}:\n" + analysed.joinToString("\n"))
            c.exec("deallocate $statement")
            scans
        }
    }

    /**
     * `search_decision_stats`' statement explained for every one of [profiles], over [shapes] (name → the committees, or
     * null for all in scope, and the years back from today); answers the sequential scans found, printing [shown]'s plans.
     */
    fun statsScans(
        c: Connection,
        tenant: UUID,
        profiles: Map<String, Profile>,
        shapes: Map<String, (Profile) -> Pair<List<UUID>?, Long>>,
        shown: String,
        label: String,
    ): List<String> {
        c.exec("set plan_cache_mode = force_custom_plan")
        val sql = c.lines("select search_decision_stats_sql()").single()
        c.exec("prepare s_stats(${STATS_TYPES.joinToString()}) as $sql")
        val scans =
            shapes.flatMap { (shape, of) ->
                profiles.mapNotNull { (name, profile) ->
                    val (committees, years) = of(profile)
                    val plan = c.lines("explain execute s_stats(${statsArguments(tenant, profile, committees, years)})")
                    plan.takeIf { lines -> lines.any { it.contains("Seq Scan on search_decision") } }?.let {
                        "stats, $name, $shape:\n${it.joinToString("\n")}"
                    }
                }
            }
        shapes.forEach { (shape, of) ->
            val profile = profiles.getValue(shown)
            val (committees, years) = of(profile)
            val analysed = c.lines("explain (analyze, buffers) execute s_stats(${statsArguments(tenant, profile, committees, years)})")
            println("$label plan, $shown, stats $shape:\n" + analysed.joinToString("\n"))
        }
        c.exec("deallocate s_stats")
        return scans
    }

    /** The values of `search_find_decisions_sql()`'s parameters \$1 to \$33 for [profile] and [shape]. */
    fun arguments(
        tenant: UUID,
        profile: Profile,
        shape: Shape,
    ): String =
        listOf(
            "'$tenant'",
            "'${profile.viewer}'",
            "${profile.clearance}",
            "${profile.all}",
            uuids(profile.officers),
            uuids(profile.members),
            "${profile.reader}",
            uuids(profile.recused),
            shape.tsquery,
            shape.refNorm,
            shape.refTail,
            uuids(shape.forums(profile)),
            "null",
            "null",
            "null",
            "null",
            "false",
            "null",
            "null",
            "null",
            "current_date",
            "false",
            "null",
            "null",
            "null",
            "null",
            "null",
            "null",
            "51",
            "2000",
            "10001",
            "${profile.reader || profile.all}",
            uuids(profile.inScope),
        ).joinToString()

    /** The values of `search_decision_stats_sql()`'s parameters \$1 to \$14 for [profile], [committees] and the last [years]. */
    fun statsArguments(
        tenant: UUID,
        profile: Profile,
        committees: List<UUID>?,
        years: Long,
    ): String =
        listOf(
            "'$tenant'",
            "'${profile.viewer}'",
            "${profile.clearance}",
            "${profile.all}",
            uuids(profile.officers),
            uuids(profile.members),
            "${profile.reader}",
            uuids(profile.recused),
            uuids(committees),
            "(current_date - interval '$years years')::date",
            "current_date",
            "${profile.reader || profile.all}",
            uuids(profile.inScope),
            "100000",
        ).joinToString()

    private fun uuids(ids: List<UUID>?) = ids?.let { "array[${it.joinToString { id -> "'$id'" }}]::uuid[]" } ?: "null::uuid[]"

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    fun Connection.lines(sql: String): List<String> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

    /** The types of `search_decision_stats_sql()`'s parameters \$1 to \$14. */
    val STATS_TYPES = "uuid uuid int boolean uuid[] uuid[] boolean uuid[] uuid[] date date boolean uuid[] int".split(" ")

    /** The types of `search_find_decisions_sql()`'s parameters \$1 to \$33. */
    val TYPES =
        (
            "uuid uuid int boolean uuid[] uuid[] boolean uuid[] tsquery text text uuid[] uuid[] date date text[] boolean " +
                "text[] uuid[] text[] date boolean date timestamptz real text bigint uuid int int int boolean uuid[]"
        ).split(" ")
}
