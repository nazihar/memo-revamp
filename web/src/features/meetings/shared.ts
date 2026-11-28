import type { QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import type { MeetingResponse, MeetingSummaryResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { mm, mmt, mmv } from './messages'
import { cityOf, whenIn } from './time'

export type Meeting = MeetingResponse | MeetingSummaryResponse
export type MeetingStatus = MeetingResponse['status']
export type MeetingMode = MeetingResponse['mode']

export const MODES: MeetingMode[] = ['IN_PERSON', 'ONLINE', 'HYBRID']

/** States before the meeting is held: the Upcoming list (defined beside "My meetings", which the home page loads). */
export { OPEN } from './mine'
/** States after it was held: the Past list. */
export const DONE: MeetingStatus[] = ['HELD', 'MINUTES_APPROVED', 'CLOSED']

/** How a meeting is named in lists and titles: its number once the agenda is published, else its title, else the committee. */
export function meetingName(m: Meeting): string {
  return m.label ?? m.title ?? m.forumName
}

/** "Board Risk Committee — BRC 3/2026", or the committee alone before the meeting is numbered. */
export function fullName(intl: IntlShape, m: Meeting): string {
  const second = m.label ?? m.title
  return second ? intl.formatMessage(mmv.nameWithLabel, { name: m.forumName, label: second }) : m.forumName
}

/** The meeting's date and times on its own clock, with the zone's city: "Thu, Dec 12, 2026 · 10:00 AM – 1:00 PM Dhaka time". */
export function whenLine(intl: IntlShape, m: Pick<Meeting, 'startsAt' | 'endsAt' | 'timeZone'>): string {
  const { date, times } = whenIn(intl, m, m.timeZone)
  return intl.formatMessage(mmv.dateAndTimes, {
    date,
    times: intl.formatMessage(mmv.zoneTimes, { times, city: cityOf(m.timeZone) }),
  })
}

const MEETING_QUERIES = new Set(['listForumMeetings', 'getMeeting', 'listMyMeetings'])

/** Reloads every meeting query after a change (a committee's list, the meeting, "My meetings"). */
export function refreshMeetings(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => MEETING_QUERIES.has((query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''),
  })
}

/** Field problems of the meeting forms in words, beside their fields (`UNKNOWN_LOCAL_TIME` at the time it is about). */
export function meetingFieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const byCode: Record<string, string> = {
    UNKNOWN_LOCAL_TIME: intl.formatMessage(mm.UNKNOWN_LOCAL_TIME),
    UNKNOWN_ZONE: intl.formatMessage(mm.UNKNOWN_ZONE),
    END_BEFORE_START: intl.formatMessage(mm.END_BEFORE_START),
    TOO_LONG: intl.formatMessage(mm.TOO_LONG),
    LINK_NOT_HTTPS: intl.formatMessage(mm.linkNotHttps),
    LINK_CREDENTIALS: intl.formatMessage(mm.linkCredentials),
    FORMAT: intl.formatMessage(mm.linkFormat),
    LENGTH: intl.formatMessage(mm.tooLongText),
    REASON_REQUIRED: intl.formatMessage(mm.REASON_REQUIRED),
  }
  const byField: Record<string, string> = {
    'location:REQUIRED': intl.formatMessage(mm.locationRequired),
    'joinUrl:REQUIRED': intl.formatMessage(mm.linkRequired),
    'classificationId:CLASSIFICATION': intl.formatMessage(mmt.CLASSIFICATION, terms),
    'classificationId:unknown': intl.formatMessage(mm.unknownLevel),
    'userId:CLASSIFICATION': intl.formatMessage(mm.guestNotCleared),
    'userId:unknown': intl.formatMessage(mm.unknownPerson),
  }
  const found: Record<string, string> = {}
  for (const e of problem.errors) {
    // The first problem of a field is the one shown.
    found[e.field] ??= byField[`${e.field}:${e.code ?? ''}`] ?? byCode[e.code ?? ''] ?? e.message
  }
  return found
}

/** One sentence for a refusal of a whole action: a known reason, a lost race, no right, or a generic failure. */
export function meetingRefusal(intl: IntlShape, terms: TermValues, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(mm.failed)
  switch (problem.reason) {
    case 'WRONG_STATE':
      return intl.formatMessage(mm.WRONG_STATE)
    case 'ALREADY_GUEST':
      return intl.formatMessage(mm.ALREADY_GUEST)
    case 'FORUM_DISSOLVED':
      return intl.formatMessage(mmt.FORUM_DISSOLVED, terms)
    case 'TYPE_NOT_PUBLISHED':
      return intl.formatMessage(mmt.typeNotPublished, terms)
  }
  if (problem.status === 412 || problem.status === 409) return intl.formatMessage(mm.conflict)
  if (problem.status === 403) return intl.formatMessage(mm.forbidden)
  return intl.formatMessage(mm.failed)
}

/** The If-Match header of a change to [m]. */
export function ifMatch(m: Pick<MeetingResponse, 'version'>): { 'If-Match': string } {
  return { 'If-Match': `"${String(m.version)}"` }
}

/** What the meeting's ⋯ menu can open. */
export type MeetingAction = 'edit' | 'reschedule' | 'schedule' | 'start' | 'end' | 'cancel'

/** The actions a meeting in [status] offers its secretaries (design §1.1; guests are on the meeting's page). */
export function actionsFor(status: MeetingStatus): MeetingAction[] {
  switch (status) {
    case 'DRAFT':
      return ['edit', 'reschedule', 'schedule', 'cancel']
    case 'SCHEDULED':
      return ['edit', 'reschedule', 'start', 'cancel']
    case 'IN_PROGRESS':
      return ['end']
    default:
      return []
  }
}
