import { defineMessages, type IntlShape } from 'react-intl'
import type { AgendaDraftResponse } from '@/api/generated/types.gen'
import type { TermValues } from '@/features/terms/terms'

/**
 * Placing a paper on an agenda, in the words both the builder's tray and the queue's Accept dialog use (flows O2 and
 * O6). The `meetings` prefix keeps them in the part loaded on the committee pages and on meetings.
 */
export const placeValues = defineMessages<
  Record<'CHAIR_APPROVAL' | 'ALLOW' | 'BLOCK' | 'next' | 'other', Record<string, string>>
>({
  CHAIR_APPROVAL: {
    id: 'meetings.place.lateChair',
    defaultMessage:
      'Late: papers were due on {date}. This goes to the chair for agreement first; it is not published or timed until the chair agrees.',
  },
  ALLOW: {
    id: 'meetings.place.lateAllow',
    defaultMessage: 'Late: papers were due on {date}. It will be published marked “Late paper”.',
  },
  BLOCK: {
    id: 'meetings.place.lateBlock',
    defaultMessage: 'Late papers are not accepted for this {forum}: papers were due on {date}. Choose a later meeting.',
  },
  next: { id: 'meetings.place.next', defaultMessage: 'Next meeting: {date}' },
  other: { id: 'meetings.place.other', defaultMessage: '{name}: {date}' },
})

export const placeMessages = defineMessages({
  tabled: {
    id: 'meetings.place.tabled',
    defaultMessage: 'The meeting is in progress: this is added as “Tabled at the meeting”.',
  },
  field: { id: 'meetings.place.field', defaultMessage: 'Place on the agenda of' },
  notNow: { id: 'meetings.place.notNow', defaultMessage: 'Not now: keep it in Ready for an agenda' },
  hint: {
    id: 'meetings.place.hint',
    defaultMessage: 'Optional. The paper is accepted and put at the end of the section where new items land.',
  },
  notStarted: {
    id: 'meetings.place.notStarted',
    defaultMessage:
      'The agenda of this meeting has not been started yet. The paper is accepted and waits in Ready for an agenda.',
  },
  frozen: {
    id: 'meetings.place.frozen',
    defaultMessage: 'The agenda of this meeting can no longer change. Choose another meeting.',
  },
  checking: { id: 'meetings.place.checking', defaultMessage: 'Checking the meeting’s agenda…' },
  elsewhere: {
    id: 'meetings.place.elsewhere',
    defaultMessage: 'This paper is already on the agenda of another meeting.',
  },
  done: { id: 'meetings.place.done', defaultMessage: 'Accepted and placed on the agenda.' },
  acceptPlace: { id: 'meetings.place.submit', defaultMessage: 'Accept and place' },
})

/** What the committee's late rule means for a paper placed now, or undefined while papers are on time. */
export function lateness(
  intl: IntlShape,
  terms: TermValues,
  draft: Pick<AgendaDraftResponse, 'deadlinePassed' | 'lateMode' | 'paperDeadline' | 'changes'>,
): { text: string; blocked: boolean } | undefined {
  if (draft.changes === 'TABLED_ONLY') return { text: intl.formatMessage(placeMessages.tabled), blocked: false }
  if (!draft.deadlinePassed) return undefined
  const date = draft.paperDeadline
    ? intl.formatDate(`${draft.paperDeadline}T12:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
    : ''
  return {
    text: intl.formatMessage(placeValues[draft.lateMode], { date, ...terms }),
    blocked: draft.lateMode === 'BLOCK',
  }
}
