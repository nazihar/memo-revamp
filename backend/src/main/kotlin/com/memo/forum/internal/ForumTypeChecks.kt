package com.memo.forum.internal

import com.memo.common.authz.Clearances
import com.memo.common.web.FieldProblem
import com.memo.forum.AgendaLimits
import com.memo.forum.AgendaPolicy
import com.memo.forum.AgendaTemplateItem
import com.memo.forum.OutcomeKind
import com.memo.forum.QuorumBasis
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumThreshold
import com.memo.forum.StandingKind
import com.memo.forum.ThresholdKind
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** What a forum-type version may say (ADR-0056, ADR-0057, design E01 §1); each problem names its field. */
@Component
class ForumTypeChecks(
    private val clearances: Clearances,
    private val json: JsonMapper,
) {
    fun require(settings: ForumTypeSettings) = requireNoProblems(problems(settings))

    fun problems(settings: ForumTypeSettings): List<FieldProblem> {
        val level = settings.defaultClassificationId
        return listOfNotNull(
            FieldProblem("name", "LENGTH", "A name of 1 to $MAX_NAME characters.").takeIf {
                settings.name.isBlank() || settings.name.length > MAX_NAME
            },
            FieldProblem("description", "LENGTH", "At most $MAX_DESCRIPTION characters.").takeIf {
                (settings.description?.length ?: 0) > MAX_DESCRIPTION
            },
            FieldProblem("defaultClassificationId", "unknown", "No such classification level.").takeIf {
                level != null && clearances.level(level) == null
            },
            FieldProblem("retention.years", "RANGE", "1 to $MAX_RETENTION_YEARS years.").takeIf {
                settings.retention?.years?.let { it !in 1..MAX_RETENTION_YEARS } == true
            },
            FieldProblem("retention.from", "unknown", "MEETING.").takeIf { settings.retention?.let { it.from != "MEETING" } == true },
        ) + attributeProblems(settings) + QuorumChecks.problems(settings.quorum, settings.memberAttributes.map { it.key }.toSet()) +
            outcomeProblems(settings) + termProblems(settings) + submissionProblems(settings) +
            AgendaChecks.problems(settings.agenda) + agendaSize(settings)
    }

    private fun attributeProblems(settings: ForumTypeSettings): List<FieldProblem> {
        val attributes = settings.memberAttributes
        val problems = mutableListOf<FieldProblem>()
        if (attributes.size >
            MAX_ATTRIBUTES
        ) {
            problems += FieldProblem("memberAttributes", "TOO_MANY", "At most $MAX_ATTRIBUTES attributes.")
        }
        attributes.forEachIndexed { i, a ->
            if (!ATTRIBUTE_KEY.matches(a.key)) {
                problems += FieldProblem("memberAttributes[$i].key", "FORMAT", "A letter, then letters and digits, at most 30.")
            }
            if (a.label.isBlank() || a.label.length > MAX_LABEL) {
                problems += FieldProblem("memberAttributes[$i].label", "LENGTH", "A label of 1 to $MAX_LABEL characters.")
            }
            if (attributes.subList(0, i).any { it.key == a.key }) {
                problems += FieldProblem("memberAttributes[$i].key", "duplicate", "Another attribute has this key.")
            }
        }
        return problems
    }

    private fun outcomeProblems(settings: ForumTypeSettings): List<FieldProblem> {
        val outcomes = settings.outcomes
        val problems = mutableListOf<FieldProblem>()
        if (outcomes.isEmpty() || outcomes.size > OutcomeKind.entries.size) {
            problems += FieldProblem("outcomes", "COUNT", "Choose 1 to ${OutcomeKind.entries.size} outcomes.")
        }
        if (outcomes.none { it.kind == OutcomeKind.APPROVED || it.kind == OutcomeKind.NOTED }) {
            problems += FieldProblem("outcomes", "NO_POSITIVE", "Use at least Approved or Noted.")
        }
        outcomes.forEachIndexed { i, o ->
            if (o.label.isBlank() || o.label.length > MAX_LABEL) {
                problems += FieldProblem("outcomes[$i].label", "LENGTH", "A label of 1 to $MAX_LABEL characters.")
            }
            if (outcomes.subList(0, i).any { it.kind == o.kind }) {
                problems += FieldProblem("outcomes[$i].kind", "OUTCOME_DUPLICATE", "This outcome is listed twice.")
            }
        }
        return problems
    }

    private fun termProblems(settings: ForumTypeSettings): List<FieldProblem> {
        val terms = settings.termPolicy
        val days = terms.reminderDays
        return listOfNotNull(
            FieldProblem("termPolicy.termMonths", "RANGE", "1 to $MAX_TERM_MONTHS months.").takeIf {
                terms.termMonths?.let { it !in 1..MAX_TERM_MONTHS } == true
            },
            FieldProblem("termPolicy.maxTerms", "RANGE", "1 to $MAX_TERMS terms.").takeIf {
                terms.maxTerms?.let { it !in 1..MAX_TERMS } == true
            },
            FieldProblem("termPolicy.reminderDays", "TOO_MANY", "At most $MAX_REMINDERS reminders.").takeIf { days.size > MAX_REMINDERS },
            FieldProblem("termPolicy.reminderDays", "RANGE", "Each 1 to $MAX_REMINDER_DAYS days, each once.").takeIf {
                days.any { it !in 1..MAX_REMINDER_DAYS } || days.toSet().size != days.size
            },
        )
    }

    private fun submissionProblems(settings: ForumTypeSettings): List<FieldProblem> {
        val policy = settings.submissionPolicy
        return listOfNotNull(
            FieldProblem("submissionPolicy.deadlineWorkingDays", "RANGE", "0 to $MAX_SUBMISSION_DAYS working days.").takeIf {
                policy.deadlineWorkingDays !in 0..MAX_SUBMISSION_DAYS
            },
            FieldProblem("submissionPolicy.triageWorkingDays", "RANGE", "1 to $MAX_SUBMISSION_DAYS working days.").takeIf {
                policy.triageWorkingDays !in 1..MAX_SUBMISSION_DAYS
            },
        )
    }

    // The column holds at most 64 KiB (forum/V5): a template that would not fit is refused here, not by the database.
    private fun agendaSize(settings: ForumTypeSettings): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("agenda", "TOO_LARGE", "The standard agenda is too long; shorten its titles.").takeIf {
                json.writeValueAsBytes(settings.agenda).size > MAX_AGENDA_BYTES
            },
        )

    companion object {
        const val MAX_NAME = 200
        const val MAX_DESCRIPTION = 2000
        const val MAX_ATTRIBUTES = 8
        const val MAX_LABEL = 60
        const val MAX_RETENTION_YEARS = 100
        const val MAX_TERM_MONTHS = 120
        const val MAX_TERMS = 20
        const val MAX_REMINDERS = 3
        const val MAX_REMINDER_DAYS = 365
        const val MAX_SUBMISSION_DAYS = 30
        const val MAX_AGENDA_BYTES = 60_000
        val ATTRIBUTE_KEY = Regex("^[a-z][a-zA-Z0-9]{0,29}$")
        val CODE = Regex("^[A-Z0-9][A-Z0-9-]{0,9}$")
    }
}

/** The quorum rule's own checks (ADR-0057), under `quorum.*`. */
object QuorumChecks {
    const val MAX_REQUIRED = 3
    const val MAX_COMPOSITION = 3
    const val MAX_NUMBER = 100
    const val MAX_DENOMINATOR = 10

    fun problems(
        rule: QuorumRule,
        attributes: Set<String>,
    ): List<FieldProblem> =
        (basisProblems(rule) + thresholdProblems(rule.threshold) + requiredProblems(rule) + compositionProblems(rule, attributes))
            .map { it.copy(field = "quorum.${it.field}") }

    private fun basisProblems(rule: QuorumRule): List<FieldProblem> {
        val seatsWrong = if (rule.basis == QuorumBasis.SEATS) !inRange(rule.seats) else rule.seats != null
        return listOfNotNull(
            FieldProblem("seats", "RANGE", "1 to $MAX_NUMBER seats, and only with a fixed number of seats.").takeIf { seatsWrong },
            FieldProblem("minimum", "RANGE", "1 to $MAX_NUMBER.").takeUnless { inRange(rule.minimum) },
        )
    }

    private fun thresholdProblems(t: QuorumThreshold): List<FieldProblem> {
        val fraction = FieldProblem("threshold", "QUORUM_INVALID", "A fraction from 1/$MAX_DENOMINATOR to 1/1.")
        val count = FieldProblem("threshold.count", "RANGE", "1 to $MAX_NUMBER.")
        return listOfNotNull(
            when (t.kind) {
                ThresholdKind.MAJORITY -> null
                ThresholdKind.FRACTION -> fraction.takeUnless { validFraction(t) }
                ThresholdKind.COUNT -> count.takeUnless { inRange(t.count) }
            },
        )
    }

    private fun validFraction(t: QuorumThreshold): Boolean {
        val n = t.numerator
        val d = t.denominator
        return if (n == null || d == null) false else d in 1..MAX_DENOMINATOR && n in 1..d
    }

    private fun requiredProblems(rule: QuorumRule): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("required", "TOO_MANY", "At most $MAX_REQUIRED requirements.").takeIf { rule.required.size > MAX_REQUIRED },
        ) +
            rule.required.mapIndexedNotNull { i, roles ->
                FieldProblem("required[$i]", "QUORUM_INVALID", "One or more different roles.").takeIf {
                    roles.isEmpty() || roles.toSet().size != roles.size
                }
            }

    private fun compositionProblems(
        rule: QuorumRule,
        attributes: Set<String>,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("composition", "TOO_MANY", "At most $MAX_COMPOSITION conditions.").takeIf {
                rule.composition.size > MAX_COMPOSITION
            },
        ) +
            rule.composition.flatMapIndexed { i, c ->
                val atLeast = c.atLeast
                listOfNotNull(
                    FieldProblem("composition[$i].attribute", "UNKNOWN_ATTRIBUTE", "Not an attribute of this type.")
                        .takeIf { c.attribute !in attributes },
                    compositionNumberProblem(i, c.majority, atLeast),
                )
            }

    private fun compositionNumberProblem(
        i: Int,
        majority: Boolean,
        atLeast: Int?,
    ): FieldProblem? {
        val both = majority == (atLeast != null)
        val outOfRange = atLeast != null && !inRange(atLeast)
        return if (both) {
            FieldProblem("composition[$i]", "QUORUM_INVALID", "Either a number or a majority.")
        } else {
            FieldProblem("composition[$i].atLeast", "RANGE", "1 to $MAX_NUMBER.").takeIf { outOfRange }
        }
    }

    private fun inRange(value: Int?): Boolean = value != null && value in 1..MAX_NUMBER
}

/** The agenda policy's own checks (ADR-0060, flow R), under `agenda.*`: the caps of an agenda, titles, minutes. */
object AgendaChecks {
    fun problems(policy: AgendaPolicy): List<FieldProblem> {
        val sections = policy.sections
        val items = sections.flatMap { it.items }
        val standing = items.mapNotNull { it.standingKind }
        return listOfNotNull(
            FieldProblem("agenda.sections", "TOO_MANY", "At most ${AgendaLimits.MAX_SECTIONS} sections.")
                .takeIf { sections.size > AgendaLimits.MAX_SECTIONS },
            FieldProblem("agenda.sections", "TOO_MANY", "At most ${AgendaLimits.MAX_ITEMS} items.")
                .takeIf { items.size > AgendaLimits.MAX_ITEMS },
            FieldProblem("agenda.sections", "RECEIVES_ONE", "New items land in one section at most.")
                .takeIf { sections.count { it.receivesSubmissions } > 1 },
        ) +
            sections.flatMapIndexed { i, section ->
                listOfNotNull(
                    FieldProblem("agenda.sections[$i].title", "LENGTH", "A title of 1 to ${AgendaLimits.MAX_SECTION_TITLE} characters.")
                        .takeIf { section.title.isBlank() || section.title.length > AgendaLimits.MAX_SECTION_TITLE },
                ) + section.items.flatMapIndexed { j, item -> itemProblems("agenda.sections[$i].items[$j]", item, standing) }
            }
    }

    private fun itemProblems(
        field: String,
        item: AgendaTemplateItem,
        standing: List<StandingKind>,
    ): List<FieldProblem> {
        val minutes = item.minutes
        return listOfNotNull(
            FieldProblem("$field.title", "LENGTH", "A title of 1 to ${AgendaLimits.MAX_ITEM_TITLE} characters.")
                .takeIf { item.title.isBlank() || item.title.length > AgendaLimits.MAX_ITEM_TITLE },
            FieldProblem("$field.minutes", "RANGE", "0 to ${AgendaLimits.MAX_MINUTES} minutes.")
                .takeIf { minutes != null && minutes !in 0..AgendaLimits.MAX_MINUTES },
            FieldProblem("$field.standingKind", "duplicate", "This standing item is already on the agenda.")
                .takeIf { item.standingKind != null && standing.count { it == item.standingKind } > 1 },
        )
    }
}
