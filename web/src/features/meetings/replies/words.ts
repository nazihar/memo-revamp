import type { QueryClient } from '@tanstack/react-query'
import type { IntlShape } from 'react-intl'
import type { MeetingResponse, ReplyResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { rm, rmt, rmv } from './messages'

export type Answer = NonNullable<ReplyResponse['answer']>
export type How = NonNullable<ReplyResponse['how']>

/** 44 px and larger text: the answers are for directors (E03 principle 5). */
export const BIG = 'h-auto min-h-12 whitespace-normal px-5 text-lg'

/** Whether "Add to my calendar" is offered: the audience and the officers of a meeting still to come (404 otherwise). */
export function calendarOffered(m: Pick<MeetingResponse, 'status' | 'relation'>): boolean {
  return m.relation !== 'READER' && (m.status === 'SCHEDULED' || m.status === 'IN_PROGRESS')
}

/** The answers a meeting held [mode] offers, in the order to show them (design E05 §1.4: `MODE_NOT_OFFERED` otherwise). */
export function offeredFor(mode: MeetingResponse['mode']): Answer[] {
  switch (mode) {
    case 'IN_PERSON':
      return ['IN_PERSON', 'APOLOGIES']
    case 'ONLINE':
      return ['REMOTE', 'APOLOGIES']
    case 'HYBRID':
      return ['IN_PERSON', 'REMOTE', 'APOLOGIES']
  }
}

/** "12 Nov, 10:14" in the reader's own zone: when an answer was given or a message sent. */
export function shortWhen(intl: IntlShape, at: string): string {
  return intl.formatDate(at, { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
}

/** "Thursday 12 December, 10:00" on the meeting's own clock. */
export function meetingWhen(intl: IntlShape, at: string, timeZone: string): string {
  return intl.formatDate(at, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    hour: 'numeric',
    minute: '2-digit',
    timeZone,
  })
}

/** A reply as the officers read it: "Yes, online", "Apologies — alternate attends", "Not yet". */
export function replyWords(intl: IntlShape, reply: Pick<ReplyResponse, 'answer' | 'alternateAttends' | 'stale'>) {
  if (!reply.answer) return intl.formatMessage(rm.replyNone)
  const answer =
    reply.answer === 'IN_PERSON'
      ? intl.formatMessage(rm.replyInPerson)
      : reply.answer === 'REMOTE'
        ? intl.formatMessage(rm.replyRemote)
        : intl.formatMessage(reply.alternateAttends ? rm.replyApologiesAlternate : rm.replyApologies)
  return reply.stale ? intl.formatMessage(rmv.staleReply, { answer }) : answer
}

/** How an answer reached memo: "in memo", "by phone". */
export function howWords(intl: IntlShape, how: How): string {
  return intl.formatMessage(how === 'IN_PERSON' ? rm.HOW_IN_PERSON : rm[how])
}

/** Who is asking: the member about their own answer, or a secretary recording someone else's ([name]). */
export interface Asker {
  /** The person the answer is for, when a secretary records it. */
  name?: string
  /** The seat's alternate, when known. */
  alternate?: string
}

/** A refused answer, in words (flow V3's refusals; flow W2's for an answer recorded on someone's behalf). */
export function answerRefusal(intl: IntlShape, terms: TermValues, problem: unknown, asker: Asker = {}): string {
  if (!isProblem(problem)) return intl.formatMessage(rm.failed)
  const field = problem.status === 422 ? problem.errors?.[0]?.code : undefined
  switch (problem.reason ?? field) {
    case 'WRONG_STATE':
      return intl.formatMessage(rm.WRONG_STATE)
    case 'MODE_NOT_OFFERED':
      return intl.formatMessage(rm.MODE_NOT_OFFERED)
    case 'ALTERNATE_NONE':
      return asker.name
        ? intl.formatMessage(rmv.alternateNoneFor, { name: asker.name })
        : intl.formatMessage(rm.ALTERNATE_NONE)
    case 'ALTERNATE_IS_MEMBER':
      return intl.formatMessage(asker.name ? rmt.alternateIsMemberFor : rmt.ALTERNATE_IS_MEMBER, terms)
    case 'ALTERNATE_BUSY':
      return asker.alternate
        ? intl.formatMessage(rmv.ALTERNATE_BUSY, { name: asker.alternate })
        : intl.formatMessage(rm.alternateBusyTheirs)
    case 'ALTERNATE_WITH_APOLOGIES':
      return intl.formatMessage(rm.ALTERNATE_WITH_APOLOGIES)
    case 'HOW_REQUIRED':
      return intl.formatMessage(rm.HOW_REQUIRED)
    case 'NOT_INVITABLE':
      return intl.formatMessage(rm.NOT_INVITABLE)
    case 'LENGTH':
      return intl.formatMessage(rm.LENGTH)
  }
  if (problem.status === 412 || problem.status === 428) return intl.formatMessage(rm.stale)
  if (problem.status === 409) return intl.formatMessage(rm.WRONG_STATE)
  if (problem.status === 403 || problem.status === 404) return intl.formatMessage(rm.forbidden)
  return intl.formatMessage(rm.failed)
}

const REPLY_QUERIES = new Set([
  'getMeeting',
  'listMyMeetings',
  'getMyMeetingRsvp',
  'getMeetingRsvps',
  'getMeetingInvitations',
])

/**
 * Reloads everything that shows an answer: the meeting, "My meetings", the replies and the panel, and with [mine] the
 * viewer's own answer (not after a change that answered it).
 */
export function refreshReplies(queryClient: QueryClient, mine: boolean): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => {
      const id = (query.queryKey[0] as { _id?: string } | undefined)?._id ?? ''
      return REPLY_QUERIES.has(id) && (mine || id !== 'getMyMeetingRsvp')
    },
  })
}

/** The If-Match header for a change to an answer at [version]; none before the first answer. */
export function ifMatchVersion(version: number | null | undefined): { 'If-Match'?: string } {
  return version == null ? {} : { 'If-Match': `"${String(version)}"` }
}
