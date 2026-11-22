import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of resolutions (Phase 4 E06 T10, flow AC): the committee's register (its Resolutions tab), one resolution's
 * page, certified extracts and the starting number. The `resolutions` prefix is its own catalogue part, loaded on the
 * committee pages and the resolution pages.
 */
export const rsm = defineMessages({
  tab: { id: 'resolutions.tab', defaultMessage: 'Resolutions' },
  year: { id: 'resolutions.year', defaultMessage: 'Year' },
  check: { id: 'resolutions.check', defaultMessage: 'Check the register' },
  none: { id: 'resolutions.none', defaultMessage: 'No resolution has been numbered yet.' },
  colNumber: { id: 'resolutions.col.number', defaultMessage: 'Number' },
  colMeeting: { id: 'resolutions.col.meeting', defaultMessage: 'Meeting' },
  colItem: { id: 'resolutions.col.item', defaultMessage: 'Item' },
  colOutcome: { id: 'resolutions.col.outcome', defaultMessage: 'Outcome' },
  colMemo: { id: 'resolutions.col.memo', defaultMessage: 'Memo' },
  restricted: { id: 'resolutions.restricted', defaultMessage: 'Restricted' },
  noMemo: { id: 'resolutions.noMemo', defaultMessage: '—' },
  more: { id: 'resolutions.more', defaultMessage: 'Show more' },
  inForce: { id: 'resolutions.inForce', defaultMessage: 'In force' },
  void: { id: 'resolutions.void', defaultMessage: 'Void' },
  failed: { id: 'resolutions.failed', defaultMessage: 'That did not work. Try again.' },
  startNumber: { id: 'resolutions.start', defaultMessage: 'Set the starting resolution number…' },
  startTitle: { id: 'resolutions.start.title', defaultMessage: 'Set the starting resolution number' },
  startLead: {
    id: 'resolutions.start.lead',
    defaultMessage:
      'For going live: the first number memo gives in a year, when earlier numbers were given on paper. Once only, before the first number; you confirm with your authenticator.',
  },
  startPeriod: { id: 'resolutions.start.period', defaultMessage: 'Numbering period' },
  startPeriodHint: {
    id: 'resolutions.start.periodHint',
    defaultMessage: 'The year (2026), the financial year (FY2026-27) or ALL, as the committee type numbers them.',
  },
  startNext: { id: 'resolutions.start.next', defaultMessage: 'The next number to give' },
  startSubmit: { id: 'resolutions.start.submit', defaultMessage: 'Confirm with your authenticator' },
  startDone: { id: 'resolutions.start.done', defaultMessage: 'The starting number is set.' },
  ALREADY_STARTED: {
    id: 'resolutions.refusal.alreadyStarted',
    defaultMessage: 'Numbers have already been given in this period, so its starting number cannot be set.',
  },
  FORMAT: {
    id: 'resolutions.refusal.format',
    defaultMessage: 'This is not a period of the committee type’s numbering. Check how its numbers start again.',
  },
  RANGE: { id: 'resolutions.refusal.range', defaultMessage: 'Give a whole number from 1.' },
  stepUpCancelled: {
    id: 'resolutions.refusal.stepUp',
    defaultMessage: 'Nothing was done: the authenticator code was not confirmed.',
  },
  cancel: { id: 'resolutions.cancel', defaultMessage: 'Cancel' },
  // One resolution
  conditions: { id: 'resolutions.page.conditions', defaultMessage: 'Conditions' },
  memo: { id: 'resolutions.page.memo', defaultMessage: 'Memo' },
  minutes: { id: 'resolutions.page.minutes', defaultMessage: 'Minutes' },
  minutesNotApproved: { id: 'resolutions.page.minutesNotApproved', defaultMessage: 'not yet approved' },
  recused: {
    id: 'resolutions.page.recused',
    defaultMessage: 'You declared an interest in this item and took no part.',
  },
  backToMeeting: { id: 'resolutions.page.backToMeeting', defaultMessage: 'Back to the meeting' },
  // Extracts
  extracts: { id: 'resolutions.extracts', defaultMessage: 'Certified extracts' },
  extractsNone: { id: 'resolutions.extracts.none', defaultMessage: 'Extracts issued: none.' },
  issue: { id: 'resolutions.extracts.issue', defaultMessage: 'Issue a certified extract' },
  issueTitle: { id: 'resolutions.extracts.issueTitle', defaultMessage: 'Issue a certified extract' },
  issueSubmit: { id: 'resolutions.extracts.issueSubmit', defaultMessage: 'Confirm with your authenticator' },
  issueApproved: {
    id: 'resolutions.extracts.issueApproved',
    defaultMessage: 'It will say that the minutes of the meeting are approved.',
  },
  issueNotApproved: {
    id: 'resolutions.extracts.issueNotApproved',
    defaultMessage: 'It will say that the minutes have not yet been approved.',
  },
  issueWhat: {
    id: 'resolutions.extracts.issueWhat',
    defaultMessage:
      'One page with the number, the outcome, the text and the conditions; never the vote, names or discussion.',
  },
  issued: {
    id: 'resolutions.extracts.issued',
    defaultMessage: 'The extract is being made. It opens from here when ready.',
  },
  pending: { id: 'resolutions.extracts.pending', defaultMessage: 'Being made…' },
  open: { id: 'resolutions.extracts.open', defaultMessage: 'Open the extract (PDF)' },
  failedExtract: { id: 'resolutions.extracts.failed', defaultMessage: 'It could not be made. Issue a new one.' },
  changedExtract: {
    id: 'resolutions.extracts.changed',
    defaultMessage: 'The resolution was corrected while the extract was made. Issue a new one.',
  },
  NOT_SECRETARY: {
    id: 'resolutions.refusal.notSecretary',
    defaultMessage: 'Only a secretary of the committee in office issues certified extracts.',
  },
  RECUSED: {
    id: 'resolutions.refusal.recused',
    defaultMessage: 'You declared an interest in this item, so another secretary issues its extract.',
  },
  VOID: { id: 'resolutions.refusal.void', defaultMessage: 'This resolution is void: no extract can be issued.' },
  NOT_RELEASED: {
    id: 'resolutions.refusal.notReleased',
    defaultMessage: 'An extract can be issued once the memo has moved on with this decision.',
  },
})

/** Texts with values. */
export const rsmv = defineMessages<{
  registerTitle: { committee: string }
  checkComplete: { first: number; last: number; inForce: number; void: number }
  checkEmpty: Record<string, never>
  checkMissing: { numbers: string; count: number }
  checkDuplicates: { numbers: string; count: number }
  checkStray: { numbers: string; count: number }
  voidReason: { reason: string }
  meetingLine: { committee: string; meeting: string; date: string; item: string }
  itemNumber: { number: string }
  itemTitle: { number: string; title: string }
  validUntil: { date: string }
  memoMoved: { reference: string; date: string }
  minutesApproved: { date: string }
  extractLine: { time: string; name: string }
  extractMinutes: { approved: string }
  fingerprint: { hash: string }
  certifyAs: { committee: string }
  numberTitle: { number: string }
  voidedOn: { date: string; reason: string }
}>({
  registerTitle: { id: 'resolutions.registerTitle', defaultMessage: '{committee} — Resolutions' },
  checkComplete: {
    id: 'resolutions.check.complete',
    defaultMessage:
      '{first} to {last} are all accounted for ({inForce, plural, one {# in force} other {# in force}}, {void, plural, one {# void} other {# void}}).',
  },
  checkEmpty: { id: 'resolutions.check.empty', defaultMessage: 'No number has been given in this period.' },
  checkMissing: {
    id: 'resolutions.check.missing',
    defaultMessage: '{count, plural, one {Number {numbers} is missing.} other {Numbers {numbers} are missing.}}',
  },
  checkDuplicates: {
    id: 'resolutions.check.duplicates',
    defaultMessage:
      '{count, plural, one {Number {numbers} is given twice.} other {Numbers {numbers} are given twice.}}',
  },
  checkStray: {
    id: 'resolutions.check.stray',
    defaultMessage:
      '{count, plural, one {Number {numbers} is outside the counter.} other {Numbers {numbers} are outside the counter.}}',
  },
  voidReason: { id: 'resolutions.voidReason', defaultMessage: 'Void: {reason}' },
  meetingLine: { id: 'resolutions.meetingLine', defaultMessage: '{committee} · {meeting} · {date} · {item}' },
  itemNumber: { id: 'resolutions.itemNumber', defaultMessage: 'item {number}' },
  itemTitle: { id: 'resolutions.itemTitle', defaultMessage: 'item {number}, {title}' },
  validUntil: { id: 'resolutions.validUntil', defaultMessage: 'Valid until {date}' },
  memoMoved: { id: 'resolutions.memoMoved', defaultMessage: '{reference} (moved on {date})' },
  minutesApproved: { id: 'resolutions.minutesApproved', defaultMessage: 'approved on {date}' },
  extractLine: { id: 'resolutions.extracts.line', defaultMessage: 'Issued {time} by {name}' },
  extractMinutes: {
    id: 'resolutions.extracts.minutes',
    defaultMessage:
      '{approved, select, yes {says the minutes are approved} other {says the minutes are not yet approved}}',
  },
  fingerprint: { id: 'resolutions.extracts.fingerprint', defaultMessage: 'Fingerprint (SHA-256): {hash}' },
  certifyAs: {
    id: 'resolutions.extracts.certifyAs',
    defaultMessage: 'You will certify this extract as a secretary of the {committee}.',
  },
  numberTitle: { id: 'resolutions.numberTitle', defaultMessage: 'Resolution {number}' },
  voidedOn: { id: 'resolutions.voidedOn', defaultMessage: 'Void since {date}: {reason}' },
})

/** Texts with the organisation's words. */
export const rsmt = defineMessages<Record<'lead', TermValues>>({
  lead: {
    id: 'resolutions.lead',
    defaultMessage:
      'Every resolution of this {forum} in number order, the latest first. A void number is never given again.',
  },
})
