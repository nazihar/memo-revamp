import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts about a paper sent to a committee (Phase 4 E02 T4, flow M) that both the committee pages and the memo page show:
 * its purpose and status, its timeline, and the memo's committee panel and note-sheet lines. The `papers` prefix is its
 * own catalogue part, loaded with `/memos` and `/committees`.
 */
export const pm = defineMessages({
  DECISION: { id: 'papers.purpose.decision', defaultMessage: 'For decision' },
  DISCUSSION: { id: 'papers.purpose.discussion', defaultMessage: 'For discussion' },
  INFORMATION: { id: 'papers.purpose.information', defaultMessage: 'For noting' },
  QUEUED: { id: 'papers.status.queued', defaultMessage: 'Waiting for triage' },
  ACCEPTED: { id: 'papers.status.accepted', defaultMessage: 'Accepted' },
  DEFERRED: { id: 'papers.status.deferred', defaultMessage: 'Deferred' },
  DECIDED: { id: 'papers.status.decided', defaultMessage: 'Decided' },
  RETURNED: { id: 'papers.status.returned', defaultMessage: 'Returned' },
  DECLINED: { id: 'papers.status.declined', defaultMessage: 'Declined' },
  WITHDRAWN: { id: 'papers.status.withdrawn', defaultMessage: 'Withdrawn' },
  REFERRED: { id: 'papers.status.referred', defaultMessage: 'Referred' },
  restricted: { id: 'papers.restricted', defaultMessage: 'Restricted' },
  restrictedHint: { id: 'papers.restricted.hint', defaultMessage: 'You are not cleared to read this memo.' },
  timeline: { id: 'papers.timeline', defaultMessage: 'What has happened to the paper' },
  acceptedAny: { id: 'papers.event.accepted', defaultMessage: 'Accepted for a meeting' },
  withdrawn: { id: 'papers.event.withdrawn', defaultMessage: 'Withdrawn with the memo' },
  nextQueued: { id: 'papers.next.queued', defaultMessage: 'Next: the secretary looks at the paper' },
  nextAccepted: { id: 'papers.next.accepted', defaultMessage: 'Next: decided at a meeting' },
  nextDeferred: { id: 'papers.next.deferred', defaultMessage: 'Next: considered at a later meeting' },
  forInformation: {
    id: 'papers.forInformation',
    defaultMessage: 'Sent for information: the memo’s route did not wait.',
  },
})

/** Texts with values. */
export const pmv = defineMessages<{
  withForum: { committee: string }
  sent: { committee: string }
  sentInfo: { committee: string }
  acceptedFor: { date: string }
  deferred: { reason: string }
  deferredTo: { reason: string; date: string }
  returned: { reason: string }
  declined: { reason: string }
  referred: { reason: string }
  decided: { outcome: string }
  by: { name: string }
  panelTitle: TermValues
  panelLead: TermValues
  committeeNote: { outcome: string; committee: string }
}>({
  withForum: { id: 'papers.withForum', defaultMessage: 'With the {committee}' },
  sent: { id: 'papers.event.sent', defaultMessage: 'Sent to the {committee}' },
  sentInfo: { id: 'papers.event.sentInfo', defaultMessage: 'Sent to the {committee} for information' },
  acceptedFor: { id: 'papers.event.acceptedFor', defaultMessage: 'Accepted for the meeting of {date}' },
  deferred: { id: 'papers.event.deferred', defaultMessage: 'Deferred: {reason}' },
  deferredTo: { id: 'papers.event.deferredTo', defaultMessage: 'Deferred to {date}: {reason}' },
  returned: { id: 'papers.event.returned', defaultMessage: 'Returned: {reason}' },
  declined: { id: 'papers.event.declined', defaultMessage: 'Declined: {reason}' },
  referred: { id: 'papers.event.referred', defaultMessage: 'Referred elsewhere: {reason}' },
  decided: { id: 'papers.event.decided', defaultMessage: 'Decided: {outcome}' },
  by: { id: 'papers.event.by', defaultMessage: 'by {name}' },
  panelTitle: { id: 'papers.panel.title', defaultMessage: '{Forums}' },
  panelLead: {
    id: 'papers.panel.lead',
    defaultMessage: 'Where this memo stands with each {forum} it was sent to, newest first.',
  },
  committeeNote: {
    id: 'papers.note.committee',
    defaultMessage:
      '{outcome, select, APPROVED {Approved by the {committee}} APPROVED_WITH_CONDITIONS {Approved with conditions by the {committee}} NOTED {Noted by the {committee}} REJECTED {Rejected by the {committee}} RETURNED {Returned by the {committee}} DECLINED {Declined by the {committee}} other {Decided by the {committee}}}',
  },
})

/** A committee's outcome in words (the decision of a DECIDED paper). */
export const outcomeLabels = defineMessages({
  APPROVED: { id: 'papers.outcome.approved', defaultMessage: 'approved' },
  APPROVED_WITH_CONDITIONS: { id: 'papers.outcome.approvedWithConditions', defaultMessage: 'approved with conditions' },
  NOTED: { id: 'papers.outcome.noted', defaultMessage: 'noted' },
  REJECTED: { id: 'papers.outcome.rejected', defaultMessage: 'rejected' },
  RETURNED: { id: 'papers.outcome.returned', defaultMessage: 'returned to the author' },
})
