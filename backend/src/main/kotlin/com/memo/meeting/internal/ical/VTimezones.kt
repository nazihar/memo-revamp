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
 * The observances cover one year before to one year after the meeting:
 * - no transition in that window (Asia/Dhaka): one STANDARD observance from 1970 at the zone's offset;
 * - a zone whose current yearly rules govern the whole window (Europe/London): one observance per rule with a yearly
 *   RRULE, the form every client reads, checked against the rules' own transitions before it is used;
 * - otherwise (a zone whose rules change within the window): one observance per transition, without RRULE.
 */
internal object VTimezones {
    private val WINDOW: Duration = Duration.ofDays(366)
    private val EPOCH: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    private const val CHECK_YEARS = 3L

    fun of(
        zone: ZoneId,
        at: Instant,
    ): VTimeZone {
        val rules = zone.rules
        val from = at.minus(WINDOW)
        val to = at.plus(WINDOW)
        val observances =
            when {
                rules.nextTransition(from)?.instant?.isAfter(to) != false -> listOf(fixed(rules.getOffset(at)))
                else -> recurring(rules, from, to) ?: explicit(rules, from, to)
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

                day == -1 -> "BYDAY=-1${code(dow)}"

                day < 0 -> "BYDAY=${code(dow)};BYMONTHDAY=${(day - DAYS_IN_WEEK + 1..day).joinToString(",")}"

                (day - 1) % DAYS_IN_WEEK == 0 -> "BYDAY=${(day - 1) / DAYS_IN_WEEK + 1}${code(dow)}"

                // "On or after the 25th" of a 31-day month is "the last": the form clients know (BYDAY=-1SU).
                month.minLength() == month.maxLength() && lastWeek == month.maxLength() -> "BYDAY=-1${code(dow)}"

                lastWeek <= month.minLength() -> "BYDAY=${code(dow)};BYMONTHDAY=${(day..lastWeek).joinToString(",")}"

                else -> null
            }
        return byDay?.let { Recur("FREQ=YEARLY;BYMONTH=${month.value};$it") }
    }

    /** One observance per transition in the window, after the observance in force when the window opens. */
    private fun explicit(
        rules: ZoneRules,
        from: Instant,
        to: Instant,
    ): List<Observance> {
        val before = rules.previousTransition(from)
        val opening =
            if (before == null) {
                fixed(rules.getOffset(from))
            } else {
                observance(rules.isDaylightSavings(before.instant), before.dateTimeBefore, before.offsetBefore, before.offsetAfter)
            }
        return listOf(opening) +
            transitions(rules, from, to).map {
                observance(rules.isDaylightSavings(it.instant), it.dateTimeBefore, it.offsetBefore, it.offsetAfter)
            }
    }

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

    private fun code(dow: DayOfWeek): String = dow.name.take(2)

    private const val DAYS_IN_WEEK = 7
    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
}
