import { defineMessages } from 'react-intl'

/**
 * Texts of the meeting page members read (Phase 4 E03 T8, flow P): the agenda as the reader may see it, papers in the
 * PDF viewer, "What changed since you last looked", consent requests and earlier revisions. The `meetingPage` prefix is
 * its own catalogue part, loaded on `/meetings` pages only.
 */
export const pm = defineMessages({
  agenda: { id: 'meetingPage.agenda', defaultMessage: 'Agenda' },
  notPublished: {
    id: 'meetingPage.notPublished',
    defaultMessage: 'The agenda has not been published yet. It appears here when the secretary publishes it.',
  },
  nothingShown: { id: 'meetingPage.nothingShown', defaultMessage: 'No item of this agenda is shown to you.' },
  print: { id: 'meetingPage.print', defaultMessage: 'Print the agenda' },
  // Revisions
  revision: { id: 'meetingPage.revision', defaultMessage: 'Revision of the agenda' },
  readLatest: { id: 'meetingPage.readLatest', defaultMessage: 'Read the latest revision' },
  showChanges: { id: 'meetingPage.showChanges', defaultMessage: 'Show changes' },
  changesTitle: { id: 'meetingPage.changes.title', defaultMessage: 'Changes since you last looked' },
  close: { id: 'meetingPage.close', defaultMessage: 'Close' },
  restrictedItem: { id: 'meetingPage.restrictedItem', defaultMessage: 'Restricted item' },
  // Items
  DECISION: { id: 'meetingPage.purpose.decision', defaultMessage: 'For decision' },
  DISCUSSION: { id: 'meetingPage.purpose.discussion', defaultMessage: 'For discussion' },
  INFORMATION: { id: 'meetingPage.purpose.information', defaultMessage: 'For noting' },
  break: { id: 'meetingPage.break', defaultMessage: 'Break' },
  papersRestricted: { id: 'meetingPage.papersRestricted', defaultMessage: '— papers restricted' },
  LATE: { id: 'meetingPage.late', defaultMessage: 'Late paper' },
  TABLED: { id: 'meetingPage.tabled', defaultMessage: 'Tabled at the meeting' },
  membersOnly: { id: 'meetingPage.membersOnly', defaultMessage: 'Members only' },
  consentHint: { id: 'meetingPage.consentHint', defaultMessage: 'Taken together without discussion' },
  // Papers
  opened: { id: 'meetingPage.paper.opened', defaultMessage: 'Opened' },
  notReadyHint: { id: 'meetingPage.paper.notReadyHint', defaultMessage: 'Being prepared: it can be read shortly.' },
  back: { id: 'meetingPage.paper.back', defaultMessage: 'Back to the agenda' },
  notReady: {
    id: 'meetingPage.paper.notReady',
    defaultMessage: 'This paper is still being prepared. Try again in a few minutes.',
  },
  viewerFailed: {
    id: 'meetingPage.paper.failed',
    defaultMessage: 'The paper could not be shown. Try again, or ask the secretary.',
  },
  loadingPaper: { id: 'meetingPage.paper.loading', defaultMessage: 'Loading the paper…' },
  // Consent
  askItem: { id: 'meetingPage.ask.item', defaultMessage: 'Ask to discuss this item' },
  askSubmit: { id: 'meetingPage.ask.submit', defaultMessage: 'Ask to discuss' },
  askExplain: {
    id: 'meetingPage.ask.explain',
    defaultMessage: 'The secretary and the chair will be told. You can withdraw this until the meeting starts.',
  },
  cancel: { id: 'meetingPage.cancel', defaultMessage: 'Cancel' },
  asked: { id: 'meetingPage.ask.done', defaultMessage: 'Your request has been sent to the secretary and the chair.' },
  withdraw: { id: 'meetingPage.withdraw', defaultMessage: 'Withdraw my request' },
  withdrawn: { id: 'meetingPage.withdraw.done', defaultMessage: 'Your request has been withdrawn.' },
  ALREADY_ASKED: {
    id: 'meetingPage.problem.alreadyAsked',
    defaultMessage: 'You have already asked to discuss this item.',
  },
  NO_REQUEST: { id: 'meetingPage.problem.noRequest', defaultMessage: 'You have no request on this item to withdraw.' },
  WRONG_STATE: {
    id: 'meetingPage.problem.wrongState',
    defaultMessage:
      'This cannot be changed now: the meeting has started or the item has moved. The page shows the latest agenda.',
  },
  forbidden: {
    id: 'meetingPage.problem.forbidden',
    defaultMessage: 'Only the members and guests of the meeting can ask for an item to be discussed.',
  },
  failed: { id: 'meetingPage.problem.failed', defaultMessage: 'That did not work. Try again in a moment.' },
})

/** Words for what a revision changed on an item, after a colon ("Changed: paper replaced"). */
export const itemFlags = defineMessages({
  ADDED: { id: 'meetingPage.flag.added', defaultMessage: 'added' },
  REMOVED: { id: 'meetingPage.flag.removed', defaultMessage: 'removed' },
  MOVED: { id: 'meetingPage.flag.moved', defaultMessage: 'moved' },
  RETITLED: { id: 'meetingPage.flag.retitled', defaultMessage: 'renamed' },
  TIME_CHANGED: { id: 'meetingPage.flag.timeChanged', defaultMessage: 'time changed' },
  PAPER_ADDED: { id: 'meetingPage.flag.paperAdded', defaultMessage: 'paper added' },
  PAPER_REPLACED: { id: 'meetingPage.flag.paperReplaced', defaultMessage: 'paper replaced' },
  PAPER_REMOVED: { id: 'meetingPage.flag.paperRemoved', defaultMessage: 'paper removed' },
  LATE: { id: 'meetingPage.flag.late', defaultMessage: 'late paper' },
  TABLED: { id: 'meetingPage.flag.tabled', defaultMessage: 'tabled at the meeting' },
})

/** Words for what a revision changed on a paper, after a colon. */
export const paperFlags = defineMessages({
  ADDED: { id: 'meetingPage.paperFlag.added', defaultMessage: 'added' },
  REPLACED: { id: 'meetingPage.paperFlag.replaced', defaultMessage: 'replaced with a new version' },
  REMOVED: { id: 'meetingPage.paperFlag.removed', defaultMessage: 'removed' },
  LATE: { id: 'meetingPage.paperFlag.late', defaultMessage: 'late paper' },
  TABLED: { id: 'meetingPage.paperFlag.tabled', defaultMessage: 'tabled at the meeting' },
})

export const pmv = defineMessages<{
  yourTimes: { times: string; city: string }
  revisionOption: { number: number; date: string }
  revisionLatest: { number: number; date: string }
  readingEarlier: { number: number; date: string }
  publishedOn: { number: number; date: string }
  changedOn: { number: number; date: string }
  note: { note: string }
  changesSince: { count: number }
  changesOf: { number: number; date: string }
  changedLine: { line: string; changes: string }
  numbered: { number: string; title: string }
  moved: { number: string }
  changed: { changes: string }
  minutes: { minutes: number }
  presenters: { count: number; names: string }
  paper: { title: string; pages: number }
  papersOf: { item: string }
  liftedBy: { names: string }
  askNamed: { item: string }
  withdrawNamed: { item: string }
  askTitle: { number: string }
  printedRevision: { number: number; date: string }
}>({
  yourTimes: { id: 'meetingPage.when.yours', defaultMessage: '({times} your time, {city})' },
  revisionOption: { id: 'meetingPage.revision.option', defaultMessage: 'Revision {number}, {date}' },
  revisionLatest: { id: 'meetingPage.revision.latest', defaultMessage: 'Revision {number}, {date} (the latest)' },
  readingEarlier: {
    id: 'meetingPage.revision.earlier',
    defaultMessage: 'You are reading revision {number} of {date}, an earlier version of the agenda.',
  },
  publishedOn: {
    id: 'meetingPage.revision.published',
    defaultMessage: 'The agenda was published on {date} (revision {number}).',
  },
  changedOn: {
    id: 'meetingPage.revision.changed',
    defaultMessage: 'The agenda changed on {date} (revision {number}).',
  },
  note: { id: 'meetingPage.revision.note', defaultMessage: '“{note}”' },
  changesSince: {
    id: 'meetingPage.changes.count',
    defaultMessage: '{count, plural, one {# change} other {# changes}} since you last looked',
  },
  changesOf: { id: 'meetingPage.changes.of', defaultMessage: 'Revision {number}, {date}' },
  changedLine: { id: 'meetingPage.changes.line', defaultMessage: '{line}: {changes}' },
  numbered: { id: 'meetingPage.numbered', defaultMessage: '{number} {title}' },
  moved: { id: 'meetingPage.flag.movedFrom', defaultMessage: 'moved (was item {number})' },
  changed: { id: 'meetingPage.changed', defaultMessage: 'Changed: {changes}' },
  minutes: { id: 'meetingPage.minutes', defaultMessage: '{minutes} min' },
  presenters: {
    id: 'meetingPage.presenters',
    defaultMessage: '{count, plural, one {Presenter} other {Presenters}}: {names}',
  },
  paper: {
    id: 'meetingPage.paper',
    defaultMessage: '{title} · {pages, plural, one {# page} other {# pages}}',
  },
  papersOf: { id: 'meetingPage.papersOf', defaultMessage: 'Papers for {item}' },
  liftedBy: { id: 'meetingPage.liftedBy', defaultMessage: 'Lifted for discussion at the request of {names}.' },
  askNamed: { id: 'meetingPage.ask.named', defaultMessage: 'Ask to discuss this item: {item}' },
  withdrawNamed: { id: 'meetingPage.withdraw.named', defaultMessage: 'Withdraw my request: {item}' },
  askTitle: { id: 'meetingPage.ask.title', defaultMessage: 'Ask for item {number} to be discussed separately?' },
  printedRevision: {
    id: 'meetingPage.printed',
    defaultMessage: 'Agenda, revision {number}, published on {date}',
  },
})
