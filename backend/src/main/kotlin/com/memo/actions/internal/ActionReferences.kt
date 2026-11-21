package com.memo.actions.internal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How an action is named and timed (design E07 §1.1): its reference, its source label and when it is due. */
object ActionReferences {
    /** "BRC A14/2026": the committee's code, the number and the year. */
    fun reference(
        code: String,
        number: Int,
        year: Int,
    ): String = "$code A$number/$year"

    /** An action is due at the end of its date in the tenant's zone: the start of the next day. */
    fun dueAt(
        dueDate: LocalDate,
        zone: ZoneId,
    ): Instant = dueDate.plusDays(1).atStartOfDay(zone).toInstant()

    /**
     * The source as lists show it: "BRC 3/2026, item 5 — Resolution BRC/2026/014, condition 2" ("— decision" until the
     * decision has its number). The meeting's number, the item's and the resolution's, never
     * the item's title or the decision's text, so the label is safe to show to an owner who may not read the item.
     */
    fun label(
        source: ActionSource,
        committee: String,
        meetingLabel: String?,
        itemNumber: String?,
        conditionIndex: Int?,
        /** The decision's resolution number in force (E06 T4), once it has one. */
        resolution: String? = null,
    ): String {
        val meeting = meetingLabel ?: "the meeting"
        val item = itemNumber?.let { "item $it" } ?: "an item"
        return when (source) {
            ActionSource.DIRECT -> {
                committee
            }

            ActionSource.MEETING -> {
                meeting
            }

            ActionSource.ITEM -> {
                "$meeting, $item"
            }

            ActionSource.DECISION -> {
                "$meeting, $item — " + (resolution?.let { "Resolution $it" } ?: "decision") +
                    (conditionIndex?.let { ", condition ${it + 1}" } ?: "")
            }
        }.take(LABEL_MAX)
    }

    private const val LABEL_MAX = 300
}
