import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of meetings (Phase 4 E03 T6, flow N): the committee page's Meetings tab, scheduling, settings, rescheduling and
 * cancelling, guests, the meeting's summary page, and "My meetings" on the home page, `/me/meetings` and the
 * "My committees" cards. The `meetings` prefix is its own catalogue part, loaded with those pages. Messages that name
 * the committee take the organisation's terms (ADR-0032).
 */
export const mm = defineMessages({
  tab: { id: 'meetings.tab', defaultMessage: 'Meetings' },
  lists: { id: 'meetings.lists', defaultMessage: 'Which meetings' },
  upcoming: { id: 'meetings.list.upcoming', defaultMessage: 'Upcoming' },
  past: { id: 'meetings.list.past', defaultMessage: 'Past' },
  cancelledList: { id: 'meetings.list.cancelled', defaultMessage: 'Cancelled' },
  schedule: { id: 'meetings.schedule', defaultMessage: 'Schedule a meeting' },
  colDate: { id: 'meetings.col.date', defaultMessage: 'Date' },
  colMeeting: { id: 'meetings.col.meeting', defaultMessage: 'Meeting' },
  colMode: { id: 'meetings.col.mode', defaultMessage: 'How' },
  colStatus: { id: 'meetings.col.status', defaultMessage: 'Status' },
  colActions: { id: 'meetings.col.actions', defaultMessage: 'Actions' },
  emptyUpcoming: { id: 'meetings.empty.upcoming', defaultMessage: 'No meetings are planned.' },
  emptyPast: { id: 'meetings.empty.past', defaultMessage: 'No meetings were held in this period.' },
  emptyCancelled: { id: 'meetings.empty.cancelled', defaultMessage: 'No meetings have been cancelled.' },
  showMore: { id: 'meetings.showMore', defaultMessage: 'Show more meetings' },
  showEarlier: { id: 'meetings.showEarlier', defaultMessage: 'Show earlier meetings' },
  // How and what kind
  IN_PERSON: { id: 'meetings.mode.inPerson', defaultMessage: 'In person' },
  ONLINE: { id: 'meetings.mode.online', defaultMessage: 'Online' },
  HYBRID: { id: 'meetings.mode.hybrid', defaultMessage: 'Hybrid' },
  hybridChoice: { id: 'meetings.mode.hybridChoice', defaultMessage: 'Both (hybrid)' },
  ORDINARY: { id: 'meetings.kind.ordinary', defaultMessage: 'Ordinary' },
  SPECIAL: { id: 'meetings.kind.special', defaultMessage: 'Special' },
  special: { id: 'meetings.kind.specialMeeting', defaultMessage: 'Special meeting' },
  // States
  DRAFT: { id: 'meetings.status.draft', defaultMessage: 'Draft' },
  SCHEDULED: { id: 'meetings.status.scheduled', defaultMessage: 'Scheduled' },
  IN_PROGRESS: { id: 'meetings.status.inProgress', defaultMessage: 'In progress' },
  HELD: { id: 'meetings.status.held', defaultMessage: 'Held' },
  MINUTES_APPROVED: { id: 'meetings.status.minutesApproved', defaultMessage: 'Minutes approved' },
  CLOSED: { id: 'meetings.status.closed', defaultMessage: 'Closed' },
  CANCELLED: { id: 'meetings.status.cancelled', defaultMessage: 'Cancelled' },
  notHeld: { id: 'meetings.status.notHeld', defaultMessage: 'Not marked as held' },
  draftHint: { id: 'meetings.draftHint', defaultMessage: 'Only the secretaries and the chair see a draft.' },
  // The meeting's page
  where: { id: 'meetings.where', defaultMessage: 'Where' },
  how: { id: 'meetings.how', defaultMessage: 'How' },
  kind: { id: 'meetings.kind', defaultMessage: 'Kind' },
  join: { id: 'meetings.join', defaultMessage: 'Join the meeting' },
  joinNewTab: { id: 'meetings.join.newTab', defaultMessage: '(opens in a new tab)' },
  placeHidden: {
    id: 'meetings.placeHidden',
    defaultMessage: 'The place and the link are shown to the people attending.',
  },
  joinDetails: { id: 'meetings.joinDetails', defaultMessage: 'Joining instructions' },
  classification: { id: 'meetings.classification', defaultMessage: 'Classification' },
  agendaLater: {
    id: 'meetings.agendaLater',
    defaultMessage: 'The agenda appears here once the secretary publishes it.',
  },
  guests: { id: 'meetings.guests', defaultMessage: 'Guests' },
  guestsHint: {
    id: 'meetings.guests.hint',
    defaultMessage: 'In attendance for the whole meeting, for example the Chief Financial Officer.',
  },
  noGuests: { id: 'meetings.guests.none', defaultMessage: 'No guests.' },
  addGuest: { id: 'meetings.guests.add', defaultMessage: 'Add a guest' },
  guestPerson: { id: 'meetings.guests.person', defaultMessage: 'Person' },
  guestNote: { id: 'meetings.guests.note', defaultMessage: 'Their role at the meeting' },
  guestNoteHint: { id: 'meetings.guests.noteHint', defaultMessage: 'For example “Chief Financial Officer”.' },
  remove: { id: 'meetings.guests.remove', defaultMessage: 'Remove' },
  removeGuestText: {
    id: 'meetings.guests.removeText',
    defaultMessage: 'They will no longer see this meeting. The record that they were invited is kept.',
  },
  actions: { id: 'meetings.actions', defaultMessage: 'Meeting actions' },
  // Actions
  editSettings: { id: 'meetings.action.edit', defaultMessage: 'Edit settings…' },
  changeTimes: { id: 'meetings.action.changeTimes', defaultMessage: 'Change the date and time…' },
  reschedule: { id: 'meetings.action.reschedule', defaultMessage: 'Reschedule…' },
  scheduleAction: { id: 'meetings.action.schedule', defaultMessage: 'Schedule…' },
  start: { id: 'meetings.action.start', defaultMessage: 'Start the meeting…' },
  end: { id: 'meetings.action.end', defaultMessage: 'Mark as held…' },
  cancel: { id: 'meetings.action.cancel', defaultMessage: 'Cancel the meeting…' },
  discard: { id: 'meetings.action.discard', defaultMessage: 'Discard the draft…' },
  openMeeting: { id: 'meetings.open', defaultMessage: 'Open meeting' },
  // The forms
  kindLegend: { id: 'meetings.form.kind', defaultMessage: 'Kind of meeting' },
  title: { id: 'meetings.form.title', defaultMessage: 'Title' },
  optional: { id: 'meetings.form.optional', defaultMessage: '(optional)' },
  date: { id: 'meetings.form.date', defaultMessage: 'Date' },
  from: { id: 'meetings.form.from', defaultMessage: 'From' },
  to: { id: 'meetings.form.to', defaultMessage: 'To' },
  otherDay: { id: 'meetings.form.otherDay', defaultMessage: 'Ends on another day' },
  endDate: { id: 'meetings.form.endDate', defaultMessage: 'Ends on' },
  timeZone: { id: 'meetings.form.timeZone', defaultMessage: 'Time zone' },
  timeZoneHint: {
    id: 'meetings.form.timeZoneHint',
    defaultMessage: 'Search by city. Without a choice, your organisation’s time zone is used.',
  },
  orgZone: { id: 'meetings.zone.organisation', defaultMessage: 'Your organisation’s time zone' },
  zoneSearching: { id: 'meetings.zone.type', defaultMessage: 'Type a city, for example Dhaka or London.' },
  zoneNone: { id: 'meetings.zone.none', defaultMessage: 'No time zone found for that city.' },
  howLegend: { id: 'meetings.form.how', defaultMessage: 'How' },
  location: { id: 'meetings.form.location', defaultMessage: 'Where' },
  locationHint: {
    id: 'meetings.form.locationHint',
    defaultMessage: 'For example “Board Room, Level 12, Head Office”.',
  },
  joinUrl: { id: 'meetings.form.joinUrl', defaultMessage: 'Join link' },
  joinUrlHint: {
    id: 'meetings.form.joinUrlHint',
    defaultMessage: 'Starts with https://. The people attending see it on the meeting page.',
  },
  joinDetailsHint: {
    id: 'meetings.form.joinDetailsHint',
    defaultMessage: 'For example the meeting ID and how to get the passcode.',
  },
  saveDraft: { id: 'meetings.form.saveDraft', defaultMessage: 'Save as draft' },
  scheduleSubmit: { id: 'meetings.form.schedule', defaultMessage: 'Schedule' },
  scheduleExplain: {
    id: 'meetings.form.scheduleExplain',
    defaultMessage:
      'Save as draft: only the secretaries and the chair see it. Schedule: the members see the date, the place and the link.',
  },
  save: { id: 'meetings.form.save', defaultMessage: 'Save' },
  reason: { id: 'meetings.form.reason', defaultMessage: 'Reason' },
  rescheduleReasonHint: {
    id: 'meetings.form.rescheduleReasonHint',
    defaultMessage: 'For example “Chair unavailable”.',
  },
  cancelReasonHint: { id: 'meetings.form.cancelReasonHint', defaultMessage: 'For example “Not enough business”.' },
  rescheduleExplain: {
    id: 'meetings.reschedule.explain',
    defaultMessage: 'The members see the new date and time, and the reason is kept in the meeting’s history.',
  },
  rescheduleSubmit: { id: 'meetings.reschedule.submit', defaultMessage: 'Reschedule' },
  cancelExplain: {
    id: 'meetings.cancel.explain',
    defaultMessage: 'Members will be told. Items from the queue go back to Ready for an agenda.',
  },
  cancelSubmit: { id: 'meetings.cancel.submit', defaultMessage: 'Cancel the meeting' },
  discardExplain: {
    id: 'meetings.discard.explain',
    defaultMessage: 'Only the secretaries and the chair saw this draft. Nobody else is told.',
  },
  discardSubmit: { id: 'meetings.discard.submit', defaultMessage: 'Discard the draft' },
  dismiss: { id: 'meetings.dismiss', defaultMessage: 'Cancel' },
  keep: { id: 'meetings.keep', defaultMessage: 'Keep the meeting' },
  scheduleConfirm: {
    id: 'meetings.schedule.explain',
    defaultMessage: 'The members will see the date, the place and the link.',
  },
  startExplain: { id: 'meetings.start.explain', defaultMessage: 'The meeting is shown as in progress.' },
  startSubmit: { id: 'meetings.start.submit', defaultMessage: 'Start the meeting' },
  endExplain: {
    id: 'meetings.end.explain',
    defaultMessage: 'The meeting is shown as held. Its date, place and guests can no longer change.',
  },
  endSubmit: { id: 'meetings.end.submit', defaultMessage: 'Mark as held' },
  // Answers
  savedDraft: { id: 'meetings.done.draft', defaultMessage: 'Saved as a draft.' },
  scheduled: { id: 'meetings.done.scheduled', defaultMessage: 'Meeting scheduled. The members can see it now.' },
  saved: { id: 'meetings.done.saved', defaultMessage: 'Saved.' },
  rescheduled: { id: 'meetings.done.rescheduled', defaultMessage: 'Meeting rescheduled.' },
  cancelled: { id: 'meetings.done.cancelled', defaultMessage: 'Meeting cancelled.' },
  discarded: { id: 'meetings.done.discarded', defaultMessage: 'Draft discarded.' },
  started: { id: 'meetings.done.started', defaultMessage: 'Meeting started.' },
  ended: { id: 'meetings.done.ended', defaultMessage: 'Meeting marked as held.' },
  guestAdded: { id: 'meetings.done.guestAdded', defaultMessage: 'Guest added.' },
  guestRemoved: { id: 'meetings.done.guestRemoved', defaultMessage: 'Guest removed.' },
  // Problems
  required: { id: 'meetings.problem.required', defaultMessage: 'Fill this in.' },
  UNKNOWN_LOCAL_TIME: {
    id: 'meetings.problem.unknownLocalTime',
    defaultMessage: 'This time does not exist on that day because the clocks change. Choose another time.',
  },
  UNKNOWN_ZONE: { id: 'meetings.problem.unknownZone', defaultMessage: 'Choose a time zone from the list.' },
  END_BEFORE_START: { id: 'meetings.problem.endBeforeStart', defaultMessage: 'The meeting must end after it starts.' },
  TOO_LONG: { id: 'meetings.problem.tooLong', defaultMessage: 'A meeting can last at most 7 days.' },
  locationRequired: { id: 'meetings.problem.locationRequired', defaultMessage: 'Say where the meeting is held.' },
  linkRequired: { id: 'meetings.problem.linkRequired', defaultMessage: 'Give the link to join online.' },
  linkFormat: {
    id: 'meetings.problem.linkFormat',
    defaultMessage: 'This is not a web address. Paste the whole link, starting with https://.',
  },
  linkNotHttps: { id: 'meetings.problem.linkNotHttps', defaultMessage: 'Use a secure link that starts with https://.' },
  linkCredentials: {
    id: 'meetings.problem.linkCredentials',
    defaultMessage: 'Leave the user name and password out of the link. Put how to join in the joining instructions.',
  },
  tooLongText: { id: 'meetings.problem.length', defaultMessage: 'This is too long.' },
  guestNotCleared: {
    id: 'meetings.problem.guestNotCleared',
    defaultMessage: 'This person is not cleared for the meeting’s classification.',
  },
  unknownPerson: { id: 'meetings.problem.unknownPerson', defaultMessage: 'Choose a person from the list.' },
  unknownLevel: { id: 'meetings.problem.unknownLevel', defaultMessage: 'Choose a level from the list.' },
  REASON_REQUIRED: { id: 'meetings.problem.reasonRequired', defaultMessage: 'Give a reason.' },
  WRONG_STATE: {
    id: 'meetings.problem.wrongState',
    defaultMessage: 'This cannot be done now: the meeting has moved on. The page shows its latest state.',
  },
  ALREADY_GUEST: { id: 'meetings.problem.alreadyGuest', defaultMessage: 'This person is already a guest.' },
  conflict: {
    id: 'meetings.problem.conflict',
    defaultMessage:
      'Someone changed this meeting after you opened it. The latest version is shown now; check it and try again.',
  },
  forbidden: { id: 'meetings.problem.forbidden', defaultMessage: 'You cannot change this meeting.' },
  failed: { id: 'meetings.problem.failed', defaultMessage: 'That did not work. Try again.' },
  // My meetings
  myTitle: { id: 'meetings.mine.title', defaultMessage: 'My meetings' },
  myUpcoming: { id: 'meetings.mine.upcoming', defaultMessage: 'Coming up' },
  myRecent: { id: 'meetings.mine.recent', defaultMessage: 'Recent' },
  myEmpty: { id: 'meetings.mine.empty', defaultMessage: 'No meetings coming up.' },
  myAll: { id: 'meetings.mine.all', defaultMessage: 'All my meetings' },
  asGuest: { id: 'meetings.mine.asGuest', defaultMessage: 'You attend as a guest.' },
})

/** Messages with values. */
export const mmv = defineMessages<{
  caption: { name: string }
  newTitle: { name: string }
  actionsFor: { name: string }
  openNamed: { name: string; date: string }
  nameWithLabel: { name: string; label: string }
  zoneTimes: { times: string; city: string }
  yourTimes: { times: string }
  onYourClock: { times: string }
  dateAndTimes: { date: string; times: string }
  dot: { first: string; second: string }
  withDetail: { label: string; detail: string }
  backTo: { name: string }
  cancelledBecause: { reason: string }
  removeGuest: { name: string }
  removeGuestTitle: { name: string }
  addGuestTitle: { name: string }
  editTitle: { name: string }
  rescheduleTitle: { name: string }
  changeTimesTitle: { name: string }
  cancelTitle: { name: string }
  discardTitle: { name: string }
  scheduleTitle: { name: string }
  startTitle: { name: string }
  endTitle: { name: string }
  notScheduled: { reason: string }
  guestsFailed: { count: number }
  nextMeeting: { when: string }
}>({
  caption: { id: 'meetings.caption', defaultMessage: 'Meetings of {name}' },
  newTitle: { id: 'meetings.new.title', defaultMessage: 'New meeting of {name}' },
  actionsFor: { id: 'meetings.actionsFor', defaultMessage: 'Actions for {name}' },
  openNamed: { id: 'meetings.openNamed', defaultMessage: 'Open the meeting {name} on {date}' },
  nameWithLabel: { id: 'meetings.nameWithLabel', defaultMessage: '{name} — {label}' },
  zoneTimes: { id: 'meetings.when.zone', defaultMessage: '{times} {city} time' },
  yourTimes: { id: 'meetings.when.yours', defaultMessage: '({times} your time)' },
  onYourClock: { id: 'meetings.when.onYourClock', defaultMessage: 'On your own clock: {times}' },
  dateAndTimes: { id: 'meetings.when.dateAndTimes', defaultMessage: '{date} · {times}' },
  dot: { id: 'meetings.dot', defaultMessage: '{first} · {second}' },
  withDetail: { id: 'meetings.withDetail', defaultMessage: '{label} ({detail})' },
  backTo: { id: 'meetings.backTo', defaultMessage: 'Back to {name}' },
  cancelledBecause: { id: 'meetings.cancelledBecause', defaultMessage: 'Cancelled. Reason: {reason}' },
  removeGuest: { id: 'meetings.guests.removeNamed', defaultMessage: 'Remove {name}' },
  removeGuestTitle: { id: 'meetings.guests.removeTitle', defaultMessage: 'Remove {name} as a guest?' },
  addGuestTitle: { id: 'meetings.guests.addTitle', defaultMessage: 'Add a guest to {name}' },
  editTitle: { id: 'meetings.edit.title', defaultMessage: 'Settings of {name}' },
  rescheduleTitle: { id: 'meetings.reschedule.title', defaultMessage: 'Reschedule {name}' },
  changeTimesTitle: { id: 'meetings.changeTimes.title', defaultMessage: 'Change the date and time of {name}' },
  cancelTitle: { id: 'meetings.cancel.title', defaultMessage: 'Cancel {name}?' },
  discardTitle: { id: 'meetings.discard.title', defaultMessage: 'Discard the draft {name}?' },
  scheduleTitle: { id: 'meetings.schedule.title', defaultMessage: 'Schedule {name}?' },
  startTitle: { id: 'meetings.start.title', defaultMessage: 'Start {name}?' },
  endTitle: { id: 'meetings.end.title', defaultMessage: 'Mark {name} as held?' },
  notScheduled: {
    id: 'meetings.done.notScheduled',
    defaultMessage: 'The meeting was saved as a draft, but it could not be scheduled: {reason}',
  },
  guestsFailed: {
    id: 'meetings.done.guestsFailed',
    defaultMessage:
      '{count, plural, one {# guest could not be added} other {# guests could not be added}}. Add them on the meeting page.',
  },
  nextMeeting: { id: 'meetings.next', defaultMessage: 'Next meeting: {when}' },
})

type TermMessage =
  | 'titleHint'
  | 'classificationHint'
  | 'committeeLevel'
  | 'CLASSIFICATION'
  | 'FORUM_DISSOLVED'
  | 'typeNotPublished'
  | 'myLead'

/** Messages that name the committee with the organisation's terms. */
export const mmt = defineMessages<Record<TermMessage, TermValues>>({
  titleHint: {
    id: 'meetings.form.titleHint',
    defaultMessage: 'For example “Strategy day”. Without a title, the {forum}’s name is shown.',
  },
  classificationHint: {
    id: 'meetings.form.classificationHint',
    defaultMessage: 'Not lower than the {forum}’s level, and not higher than your own clearance.',
  },
  committeeLevel: { id: 'meetings.form.committeeLevel', defaultMessage: 'The {forum}’s level' },
  CLASSIFICATION: {
    id: 'meetings.problem.classification',
    defaultMessage: 'Choose a level no lower than the {forum}’s and no higher than your own clearance.',
  },
  FORUM_DISSOLVED: {
    id: 'meetings.problem.forumDissolved',
    defaultMessage: '{forumGender, select, other {This {forum} is dissolved and holds no more meetings.}}',
  },

  typeNotPublished: {
    id: 'meetings.problem.typeNotPublished',
    defaultMessage: 'The {forum}’s type has no published version yet. Ask an administrator to publish it.',
  },
  myLead: {
    id: 'meetings.mine.lead',
    defaultMessage: 'The meetings you attend, and those of the {forums} you run, from the last 30 days on.',
  },
})
