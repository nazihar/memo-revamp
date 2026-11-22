package com.memo.workflow.calendar

import java.time.LocalDate

/** Counting working days backwards (design E03 §1.4: the paper deadline) and forwards (E06: the comment deadline), pure. */
object WorkingDayRules {
    /** How far back [before] looks: two years, more than any deadline needs; a calendar with no working day gives null. */
    const val MAX_DAYS = 731

    /**
     * The [n]-th working day before [date], [date] not counted: [n] working days lie in `[result, date)`, the result is
     * one of them, and it moves earlier as [n] grows. [date] itself when [n] is 0; null when none is found in [MAX_DAYS].
     */
    fun before(
        date: LocalDate,
        n: Int,
        isWorkingDay: (LocalDate) -> Boolean,
    ): LocalDate? {
        require(n >= 0) { "a count of working days is not negative" }
        return if (n == 0) {
            date
        } else {
            generateSequence(date.minusDays(1)) { it.minusDays(1) }
                .take(MAX_DAYS)
                .filter(isWorkingDay)
                .drop(n - 1)
                .firstOrNull()
        }
    }

    /**
     * The [n]-th working day after [date], [date] not counted (design E06 §1.6.3: the minutes' comment deadline; design
     * E07 §1.5: when an action with no recent update turns amber): [n] working days lie in `(date, result]`. [date] itself
     * when [n] is 0; null when none is found in [MAX_DAYS].
     */
    fun after(
        date: LocalDate,
        n: Int,
        isWorkingDay: (LocalDate) -> Boolean,
    ): LocalDate? {
        require(n >= 0) { "a count of working days is not negative" }
        return if (n == 0) {
            date
        } else {
            generateSequence(date.plusDays(1)) { it.plusDays(1) }
                .take(MAX_DAYS)
                .filter(isWorkingDay)
                .drop(n - 1)
                .firstOrNull()
        }
    }
}
