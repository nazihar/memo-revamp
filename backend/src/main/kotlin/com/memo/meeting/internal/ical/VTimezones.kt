package com.memo.meeting.internal.ical

import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.Daylight
import net.fortuna.ical4j.model.component.Observance
import net.fortuna.ical4j.model.component.Standard
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.TzId
import net.fortuna.ical4j.model.property.TzName
import net.fortuna.ical4j.model.property.TzOffsetFrom
import net.fortuna.ical4j.model.property.TzOffsetTo
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import kotlin.math.abs

/**
 * A VTIMEZONE built from the JVM's zone rules (ADR-0063), so the invitation describes exactly the offsets the app used
 * for the meeting's instants; ical4j's bundled definitions and its tzurl.org updater are never involved.
 *
 * Which form, decided over a window of one year before to one year after the meeting:
 * - no transition in that window (Asia/Dhaka): one STANDARD observance from 1970 at the zone's offset;
 * - a zone whose current yearly rules govern the whole window (Europe/London): one observance per rule with a yearly
 *   RRULE, the form every client reads, checked against the rules' own transitions before it is used;
 * - otherwise (a zone whose offsets change by one-off decisions, such as Europe/Volgograd or America/Caracas): only the
 *   observance in force when the meeting starts, and one for each change during the meeting, without RRULE. The
 *   observance in force is then the last one in the file. A reader that takes the last observance's offset instead of
 *   the one whose onset precedes the time still reads the meeting right: biweekly 0.6.8 does that (it read a Volgograd
 *   meeting of 1 January 2020 at +03 from a file that also listed the change of December 2020), and a calendar client
 *   may too. Changes outside the meeting say nothing about it.
 */
internal object VTimezones {
    private val WINDOW: Duration = Duration.ofDays(366)
    private val EPOCH: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    private const val CHECK_YEARS = 3L
    private const val MAX_LOOK_BACK = 8

    /** The VTIMEZONE for a meeting from [at] to [until] (the same instant when only one time is described). */
    fun of(
        zone: ZoneId,
        at: Instant,
        until: Instant = at,
    ): VTimeZone {
        val rules = zone.rules
        val from = at.minus(WINDOW)
        val to = at.plus(WINDOW)
        val observances =
            when {
                rules.nextTransition(from)?.instant?.isAfter(to) != false -> listOf(fixed(rules.getOffset(at)))
                else -> recurring(rules, from, to) ?: explicit(rules, at, maxOf(at, until))
            }
        return VTimeZone().apply {
            add<VTimeZone>(TzId(zone.id))
            observances.forEach { add<VTimeZone>(it) }
        }
    }

    private fun fixed(offset: ZoneOffset): Observance = observance(false, EPOCH, offset, offset)

    /**
     * One observance per yearly rule, each starting at its change in the year before the window opens (so every instant
     * of the window follows an onset), when those rules alone produce every transition from there on; else null.
     */
    private fun recurring(
        rules: ZoneRules,
        from: Instant,
        to: Instant,
    ): List<Observance>? {
        val last = rules.transitions.lastOrNull()
        val yearly = rules.transitionRules.map { rule -> rule to recurrence(rule) }
        val year = from.atOffset(ZoneOffset.UTC).year - 1
        val built = yearly.mapNotNull { (rule, recur) -> recur?.let { rule to it } }
        val onset = built.minOfOrNull { (rule, _) -> rule.createTransition(year).instant }
        val usable = built.size == yearly.size && (last == null || onset == null || last.instant.isBefore(onset))
        return if (onset != null && usable && agrees(rules, built, year, onset, to)) {
            built.map { (rule, recur) ->
                val first = rule.createTransition(year)
                observance(rules.isDaylightSavings(first.instant), first.dateTimeBefore, first.offsetBefore, first.offsetAfter)
                    .apply { add<Observance>(RRule<LocalDateTime>(recur)) }
            }
        } else {
            null
        }
    }

    /** What a client computes from the RRULEs (local times in the offset before each change) is what the JVM does. */
    private fun agrees(
        rules: ZoneRules,
        built: List<Pair<ZoneOffsetTransitionRule, Recur<LocalDateTime>>>,
        year: Int,
        from: Instant,
        to: Instant,
    ): Boolean {
        val expected = transitions(rules, from, to.plus(WINDOW.multipliedBy(CHECK_YEARS)))
        val until = expected.last()
        val produced =
            built
                .flatMap { (rule, recur) ->
                    val first = rule.createTransition(year).dateTimeBefore
                    recur.getDates(first, first, until.dateTimeBefore.plusDays(1)).map { it.toInstant(rule.offsetBefore) }
                }.filter { !it.isBefore(from) && !it.isAfter(until.instant) }
                .sorted()
        return produced == expected.map { it.instant }
    }

    /** The yearly RRULE of [rule], or null when RRULE cannot say it (a 24:00 change, a day that may roll into next month). */
    private fun recurrence(rule: ZoneOffsetTransitionRule): Recur<LocalDateTime>? {
        val dow = rule.dayOfWeek
        val day = rule.dayOfMonthIndicator
        val month = rule.month
        val lastWeek = day + DAYS_IN_WEEK - 1
        val byDay =
            when {
                rule.isMidnightEndOfDay -> null

                dow == null -> "BYMONTHDAY=$day"

                day == -1 -> "BYDAY=-1${dow.code}"

                day < 0 -> "BYDAY=${dow.code};BYMONTHDAY=${(day - DAYS_IN_WEEK + 1..day).joinToString(",")}"

                (day - 1) % DAYS_IN_WEEK == 0 -> "BYDAY=${(day - 1) / DAYS_IN_WEEK + 1}${dow.code}"

                // "On or after the 25th" of a 31-day month is "the last": the form clients know (BYDAY=-1SU).
                month.minLength() == month.maxLength() && lastWeek == month.maxLength() -> "BYDAY=-1${dow.code}"

                lastWeek <= month.minLength() -> "BYDAY=${dow.code};BYMONTHDAY=${(day..lastWeek).joinToString(",")}"

                else -> null
            }
        return byDay?.let { Recur("FREQ=YEARLY;BYMONTH=${month.value};$it") }
    }

    /**
     * The observance in force at [start], and one per change up to [end]. When the one in force is daylight saving, the
     * standard observance before it comes first: a VTIMEZONE without STANDARD is unusual for clients, and biweekly takes
     * its base offset from STANDARD (the E05 T1 property tests found a lone DAYLIGHT for Africa/Cairo read two hours off).
     */
    private fun explicit(
        rules: ZoneRules,
        start: Instant,
        end: Instant,
    ): List<Observance> {
        // The change that set the offset in force at the start (at the start itself counts), then any during the meeting.
        val opening = rules.previousTransition(start.plusNanos(1))
        val first =
            when {
                opening == null -> listOf(fixed(rules.getOffset(start)))
                rules.isDaylightSavings(opening.instant) -> listOf(standardBefore(rules, opening), observance(rules, opening))
                else -> listOf(observance(rules, opening))
            }
        return first + transitions(rules, start.plusNanos(1), end).map { observance(rules, it) }
    }

    /** The latest change into standard time before [daylight], else a standard observance from 1970. */
    private fun standardBefore(
        rules: ZoneRules,
        daylight: ZoneOffsetTransition,
    ): Observance {
        val standard =
            generateSequence(rules.previousTransition(daylight.instant)) { rules.previousTransition(it.instant) }
                .take(MAX_LOOK_BACK)
                .firstOrNull { !rules.isDaylightSavings(it.instant) }
        return if (standard == null) fixed(daylight.offsetBefore) else observance(rules, standard)
    }

    private fun observance(
        rules: ZoneRules,
        change: ZoneOffsetTransition,
    ): Observance = observance(rules.isDaylightSavings(change.instant), change.dateTimeBefore, change.offsetBefore, change.offsetAfter)

    private fun transitions(
        rules: ZoneRules,
        from: Instant,
        to: Instant,
    ): List<ZoneOffsetTransition> =
        generateSequence(rules.nextTransition(from.minusNanos(1))) { rules.nextTransition(it.instant) }
            .takeWhile { !it.instant.isAfter(to) }
            .toList()

    private fun observance(
        daylight: Boolean,
        onset: LocalDateTime,
        before: ZoneOffset,
        after: ZoneOffset,
    ): Observance =
        (if (daylight) Daylight() else Standard()).apply {
            add<Observance>(DtStart(onset))
            add<Observance>(TzOffsetFrom(before))
            add<Observance>(TzOffsetTo(after))
            add<Observance>(TzName(name(after)))
        }

    /** A numeric abbreviation as tzdata writes one for zones without letters: `+06`, `+0530`, `-03`. */
    private fun name(offset: ZoneOffset): String {
        val seconds = offset.totalSeconds
        val hours = abs(seconds) / SECONDS_PER_HOUR
        val minutes = abs(seconds) % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val sign = if (seconds < 0) "-" else "+"
        return if (minutes == 0) "%s%02d".format(sign, hours) else "%s%02d%02d".format(sign, hours, minutes)
    }

    private val DayOfWeek.code: String get() = name.take(2)

    private const val DAYS_IN_WEEK = 7
    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
}
