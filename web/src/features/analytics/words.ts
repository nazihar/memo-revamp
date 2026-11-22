import { useQuery } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import { getCommitteeAnalyticsDetailOptions } from '@/api/generated/@tanstack/react-query.gen'
import type {
  CommitteeSummaryResponse,
  MemberAttendanceResponse,
  MemberMeetingResponse,
} from '@/api/generated/types.gen'
import type { PeriodSearch } from './analytics-params'
import { am, amv } from './messages'
import { formatPercent, periodQuery, quarterParts } from './period'

/** A committee's detail for the period in the address (flows AV, AW and a member's meetings share one request). */
export function useCommitteeDetail(forumId: string, search: PeriodSearch) {
  return useQuery({
    ...getCommitteeAnalyticsDetailOptions({ path: { forumId }, query: periodQuery(search) }),
    retry: false,
  })
}

/** The look of a figure's link; its arrow (`Arrow`) turns round in right-to-left languages. */
export const LINK =
  'inline-flex min-h-10 min-w-10 items-center gap-1.5 text-primary underline underline-offset-4 hover:decoration-2'

export function roleWords(intl: IntlShape, role: MemberAttendanceResponse['role']) {
  return intl.formatMessage(am[`role${role}`])
}

/** A meeting's attendance in words, with what else happened ("Attended remotely (joined late)"). */
export function seatWords(intl: IntlShape, meeting: MemberMeetingResponse) {
  const seat = intl.formatMessage(am[`seat${meeting.attendance}`])
  const notes = [
    meeting.byAlternate ? intl.formatMessage(am.byAlternateWord) : null,
    meeting.joinedLate ? intl.formatMessage(am.joinedLateWord) : null,
    meeting.leftEarly ? intl.formatMessage(am.leftEarlyWord) : null,
  ].filter((n): n is string => n !== null)
  return notes.length === 0
    ? seat
    : intl.formatMessage(amv.seatWithNotes, { seat, notes: intl.formatList(notes, { type: 'conjunction' }) })
}

/** A share in percent, or "None" when there was nothing to share. */
export function rateWords(intl: IntlShape, percent: number | null | undefined) {
  return percent == null ? intl.formatMessage(am.none) : formatPercent(intl, percent)
}

/** The committee detail's name for a seat holder; the server may have no name for a removed account. */
export function nameOf(intl: IntlShape, row: MemberAttendanceResponse) {
  return row.name ?? intl.formatMessage(am.unnamed)
}

/** By name, or by rate (highest first, then by name), as the reader chose; never a rank. */
export function sortMembers(rows: readonly MemberAttendanceResponse[], sort: 'rate' | undefined, locale: string) {
  const byName = (a: MemberAttendanceResponse, b: MemberAttendanceResponse) =>
    (a.name ?? '').localeCompare(b.name ?? '', locale) || a.userId.localeCompare(b.userId)
  return [...rows].sort((a, b) =>
    sort === 'rate' ? (b.ratePercent ?? -1) - (a.ratePercent ?? -1) || byName(a, b) : byName(a, b),
  )
}

/** "2026-Q1" in the reader's language: "Q1 2026". */
export function quarterWords(intl: IntlShape, quarter: string) {
  const parts = quarterParts(quarter)
  if (!parts) return quarter
  return intl.formatMessage(amv.quarter, {
    quarter: parts.quarter,
    year: intl.formatNumber(parts.year, { useGrouping: false }),
  })
}

/** The sentence before a committee's table: "92 % attendance at 12 meetings · 3 actions overdue · decisions took…". */
export function summarySentence(intl: IntlShape, c: CommitteeSummaryResponse) {
  const attendance =
    c.attendance.ratePercent == null || c.attendance.heldMeetings === 0
      ? intl.formatMessage(am.noMeetingsPart)
      : intl.formatMessage(amv.summaryAttendance, {
          rate: formatPercent(intl, c.attendance.ratePercent),
          meetings: c.attendance.heldMeetings,
        })
  const actions = intl.formatMessage(amv.summaryOverdue, { count: c.actions.overdue })
  const t = c.timeToDecision
  const timing =
    t.enough && t.medianDays != null
      ? intl.formatMessage(amv.summaryMedian, { days: t.medianDays })
      : intl.formatMessage(t.decisions === 0 ? am.noDecisions : am.tooFew)
  return intl.formatMessage(amv.summary, { attendance, actions, timing })
}
