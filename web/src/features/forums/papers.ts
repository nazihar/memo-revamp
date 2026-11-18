import type { IntlShape } from 'react-intl'
import { listForumSubmissionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { SubmissionResponse } from '@/api/generated/types.gen'
import type { Tone } from '@/components/ui/badge'
import { outcomeLabels, pm, pmv } from './papers-messages'
import { formatDay } from './shared'

export type Submission = SubmissionResponse
export type SubmissionStatus = Submission['status']

/** Statuses still with the committee (ADR-0059): the queue's default list. */
export const OPEN_STATUSES: SubmissionStatus[] = ['QUEUED', 'ACCEPTED', 'DEFERRED']

export function isOpen(s: Submission): boolean {
  return OPEN_STATUSES.includes(s.status)
}

export const paperTone: Record<SubmissionStatus, Tone> = {
  QUEUED: 'warn',
  ACCEPTED: 'ok',
  DEFERRED: 'neutral',
  DECIDED: 'ok',
  RETURNED: 'warn',
  DECLINED: 'danger',
  WITHDRAWN: 'neutral',
  REFERRED: 'neutral',
}

/** One line of a paper's timeline: done (with its time) or what comes next. */
export interface PaperEvent {
  key: string
  at?: string
  text: string
  by?: string
  next?: boolean
}

/**
 * A paper's story as its row tells it (flow M2): sent, then the latest triage (accepted for a date, deferred, returned,
 * declined, referred), then the decision, then what comes next while it is open. Earlier triage steps of the same paper
 * are in the audit log; a returned memo sent again is a new paper.
 */
export function paperEvents(intl: IntlShape, s: Submission): PaperEvent[] {
  const events: PaperEvent[] = [
    {
      key: 'sent',
      at: s.submittedAt,
      text: intl.formatMessage(s.waits ? pmv.sent : pmv.sentInfo, { committee: s.forumName }),
    },
  ]
  const by = s.triagedBy?.name
  const reason = s.reason ?? ''
  const triaged = (text: string) => {
    events.push({ key: 'triage', at: s.triagedAt ?? undefined, text, by })
  }
  if (s.triagedAt) {
    if (s.status === 'ACCEPTED' || (s.status === 'DECIDED' && !s.reason)) {
      triaged(
        s.targetDate
          ? intl.formatMessage(pmv.acceptedFor, { date: formatDay(intl, s.targetDate) })
          : intl.formatMessage(pm.acceptedAny),
      )
    } else if (s.status === 'DEFERRED') {
      triaged(
        s.targetDate
          ? intl.formatMessage(pmv.deferredTo, { reason, date: formatDay(intl, s.targetDate) })
          : intl.formatMessage(pmv.deferred, { reason }),
      )
    } else if (s.status === 'RETURNED') {
      triaged(intl.formatMessage(pmv.returned, { reason }))
    } else if (s.status === 'DECLINED') {
      triaged(intl.formatMessage(pmv.declined, { reason }))
    } else if (s.status === 'REFERRED') {
      triaged(intl.formatMessage(pmv.referred, { reason }))
    }
  }
  if (s.status === 'WITHDRAWN') events.push({ key: 'withdrawn', text: intl.formatMessage(pm.withdrawn) })
  if (s.status === 'DECIDED') {
    events.push({
      key: 'decided',
      at: s.decidedAt ?? undefined,
      text: intl.formatMessage(pmv.decided, {
        outcome: s.outcome ? intl.formatMessage(outcomeLabels[s.outcome]) : '',
      }),
    })
  }
  const next = s.status === 'QUEUED' ? pm.nextQueued : s.status === 'ACCEPTED' ? pm.nextAccepted : undefined
  if (s.status === 'DEFERRED') events.push({ key: 'next', text: intl.formatMessage(pm.nextDeferred), next: true })
  if (next) events.push({ key: 'next', text: intl.formatMessage(next), next: true })
  return events
}

/** The open papers of [forumId] (the queue's default), shared by the Submissions tab and its count. */
export function openPapersOptions(forumId: string) {
  return listForumSubmissionsOptions({ path: { forumId } })
}

/** "CR/2026-27/0042 Term loan, Delta Textiles", or "Restricted" when the reader is not cleared for the memo. */
export function paperName(intl: IntlShape, s: Submission): string {
  return s.memo ? `${s.memo.reference ?? ''} ${s.memo.subject}`.trim() : intl.formatMessage(pm.restricted)
}
