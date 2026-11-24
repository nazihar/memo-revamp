import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Committee analytics (Phase 4 E08 T7, flows AU–AW). These load with `/analytics` (the `analytics` catalogue part), never
 * with the core part. `{Forum}` and its kin are the organisation's terms (ADR-0032).
 */
export const am = defineMessages({
  overviewLead: {
    id: 'analytics.overview.lead',
    defaultMessage: 'These figures count only the meetings, decisions and actions you can see.',
  },

  period: { id: 'analytics.period', defaultMessage: 'Period' },
  period12m: { id: 'analytics.period.12m', defaultMessage: 'Last 12 months' },
  period2y: { id: 'analytics.period.2y', defaultMessage: 'Last 2 years' },
  period5y: { id: 'analytics.period.5y', defaultMessage: 'Last 5 years' },
  periodThisYear: { id: 'analytics.period.thisYear', defaultMessage: 'This year so far' },
  periodLastYear: { id: 'analytics.period.lastYear', defaultMessage: 'Last calendar year' },
  periodCustom: { id: 'analytics.period.custom', defaultMessage: 'Choose dates' },
  from: { id: 'analytics.period.from', defaultMessage: 'From' },
  to: { id: 'analytics.period.to', defaultMessage: 'To' },
  apply: { id: 'analytics.period.apply', defaultMessage: 'Show' },
  periodTooLong: {
    id: 'analytics.period.tooLong',
    defaultMessage: 'A period can be at most 10 years. Choose a shorter one.',
  },
  periodBackwards: {
    id: 'analytics.period.backwards',
    defaultMessage: 'The period must end on or after the day it starts.',
  },

  colMeasure: { id: 'analytics.col.measure', defaultMessage: 'Measure' },
  colValue: { id: 'analytics.col.value', defaultMessage: 'Value' },
  colDetail: { id: 'analytics.col.detail', defaultMessage: 'Detail' },
  measureAttendance: { id: 'analytics.measure.attendance', defaultMessage: 'Attendance' },
  measureOverdue: { id: 'analytics.measure.overdue', defaultMessage: 'Actions overdue now' },
  measureClosed: { id: 'analytics.measure.closed', defaultMessage: 'Actions closed on time' },
  measureTiming: { id: 'analytics.measure.timing', defaultMessage: 'Time to decision' },
  measureDeferred: { id: 'analytics.measure.deferred', defaultMessage: 'Items deferred' },
  measurePapers: { id: 'analytics.measure.papers', defaultMessage: 'Papers on time' },
  noMeetingsPart: { id: 'analytics.summary.noMeetings', defaultMessage: 'no meetings held' },
  noMeetings: { id: 'analytics.noMeetings', defaultMessage: 'No meeting was held in this period.' },
  noActionsClosed: { id: 'analytics.noActionsClosed', defaultMessage: 'No action was closed in this period.' },
  noDecisions: { id: 'analytics.noDecisions', defaultMessage: 'No decision you can see in full.' },
  tooFew: { id: 'analytics.tooFew', defaultMessage: 'Too few decisions to show a typical time' },
  noDeadline: { id: 'analytics.noDeadline', defaultMessage: 'No meeting had a papers deadline.' },
  none: { id: 'analytics.none', defaultMessage: 'None' },
  notShown: { id: 'analytics.notShown', defaultMessage: 'Not shown' },

  linkAttendance: { id: 'analytics.link.attendance', defaultMessage: 'Attendance per member' },
  // Into the decision register (E08 T6, design §2.6)
  linkDecisions: { id: 'analytics.link.decisions', defaultMessage: 'Open these decisions' },
  linkDeferred: { id: 'analytics.link.deferred', defaultMessage: 'Open deferred items' },
  linkBackToAttendance: { id: 'analytics.link.backToAttendance', defaultMessage: 'Back to attendance' },

  // AW, a committee's detail
  timingTitle: { id: 'analytics.timing.title', defaultMessage: 'Time from submission to decision' },
  timingNone: { id: 'analytics.timing.none', defaultMessage: 'No decision on a submission you can see in full.' },
  timingTooFew: {
    id: 'analytics.timing.tooFew',
    defaultMessage: 'Too few decisions to show a typical time.',
  },
  colQuarter: { id: 'analytics.col.quarter', defaultMessage: 'Quarter' },
  colDecisions: { id: 'analytics.col.decisions', defaultMessage: 'Decisions' },
  colMedian: { id: 'analytics.col.median', defaultMessage: 'Median' },
  colP90: { id: 'analytics.col.p90', defaultMessage: '9 in 10 within' },
  workingDays: { id: 'analytics.workingDays', defaultMessage: 'Count working days' },
  workingDaysHint: {
    id: 'analytics.workingDays.hint',
    defaultMessage: 'Calendar days by default; working days follow the business calendar.',
  },
  noMemoType: { id: 'analytics.noMemoType', defaultMessage: 'No memo type' },
  deferralsTitle: { id: 'analytics.deferrals.title', defaultMessage: 'Items deferred' },
  colDeferred: { id: 'analytics.col.deferred', defaultMessage: 'Deferred' },
  colShare: { id: 'analytics.col.share', defaultMessage: 'Share' },
  actionsTitle: { id: 'analytics.actions.title', defaultMessage: 'Actions overdue by owner' },
  actionsNone: { id: 'analytics.actions.none', defaultMessage: 'No action you can see is overdue.' },
  colOwner: { id: 'analytics.col.owner', defaultMessage: 'Owner' },
  colOverdue: { id: 'analytics.col.overdue', defaultMessage: 'Overdue' },
  col1To30: { id: 'analytics.col.1to30', defaultMessage: '1–30 days' },
  col31To90: { id: 'analytics.col.31to90', defaultMessage: '31–90 days' },
  colOver90: { id: 'analytics.col.over90', defaultMessage: 'Over 90 days' },
  colRevised: { id: 'analytics.col.revised', defaultMessage: 'Date changed' },
  unknownOwner: { id: 'analytics.unknownOwner', defaultMessage: 'Owner not known' },
  papersTitle: { id: 'analytics.papers.title', defaultMessage: 'Papers on time' },
  colMeeting: { id: 'analytics.col.meeting', defaultMessage: 'Meeting' },
  colDate: { id: 'analytics.col.date', defaultMessage: 'Date' },
  colPapers: { id: 'analytics.col.papers', defaultMessage: 'Agenda and papers' },
  colLateItems: { id: 'analytics.col.lateItems', defaultMessage: 'Late items' },
  colLatePapers: { id: 'analytics.col.latePapers', defaultMessage: 'Late papers' },
  colAttendedOf: { id: 'analytics.col.attendedOf', defaultMessage: 'Attendance' },
  onTime: { id: 'analytics.papers.onTime', defaultMessage: 'On time' },
  late: { id: 'analytics.papers.late', defaultMessage: 'Late' },
  noDeadlineWord: { id: 'analytics.papers.noDeadline', defaultMessage: 'No deadline' },
  papersNone: { id: 'analytics.papers.none', defaultMessage: 'No meeting was held in this period.' },

  // AV, attendance per member
  attendanceTitle: { id: 'analytics.attendance.title', defaultMessage: 'Attendance' },
  export: { id: 'analytics.attendance.export', defaultMessage: 'Export for the annual report' },
  exported: { id: 'analytics.attendance.exported', defaultMessage: 'The attendance workbook has been downloaded.' },
  exportFailed: { id: 'analytics.attendance.exportFailed', defaultMessage: 'The export could not be made. Try again.' },
  exportTooMany: {
    id: 'analytics.attendance.exportTooMany',
    defaultMessage: 'You have exported 5 times in the last hour. Please try again later.',
  },
  colMember: { id: 'analytics.col.member', defaultMessage: 'Member' },
  colRole: { id: 'analytics.col.role', defaultMessage: 'Role' },
  colExpected: { id: 'analytics.col.expected', defaultMessage: 'Expected' },
  colAttended: { id: 'analytics.col.attended', defaultMessage: 'Attended' },
  colRemote: { id: 'analytics.col.remote', defaultMessage: 'Of which remote' },
  colApologies: { id: 'analytics.col.apologies', defaultMessage: 'Apologies' },
  colAbsent: { id: 'analytics.col.absent', defaultMessage: 'Absent' },
  colRate: { id: 'analytics.col.rate', defaultMessage: 'Rate' },
  sortByName: { id: 'analytics.sort.name', defaultMessage: 'Sort by name' },
  sortByRate: { id: 'analytics.sort.rate', defaultMessage: 'Sort by rate' },
  altNote: {
    id: 'analytics.attendance.altNote',
    defaultMessage:
      '“By alternate”: an alternate attended in the member’s place; this is not counted as the member’s attendance.',
  },
  membersOmitted: {
    id: 'analytics.attendance.membersOmitted',
    defaultMessage:
      'Attendance per member covers at most 5 years. Choose a shorter period to see each member and to export.',
  },
  noRows: { id: 'analytics.attendance.noRows', defaultMessage: 'Nobody held a seat at a meeting in this period.' },
  noOwnRow: { id: 'analytics.attendance.noOwnRow', defaultMessage: 'You held no seat at a meeting in this period.' },
  memberTitle: { id: 'analytics.member.title', defaultMessage: 'Meetings and attendance' },
  memberNone: { id: 'analytics.member.none', defaultMessage: 'This person held no seat at a meeting in this period.' },
  colAttendance: { id: 'analytics.col.attendance', defaultMessage: 'Attendance' },
  unnamed: { id: 'analytics.unnamed', defaultMessage: 'Name not available' },

  roleCHAIR: { id: 'analytics.role.chair', defaultMessage: 'Chair' },
  roleVICE_CHAIR: { id: 'analytics.role.viceChair', defaultMessage: 'Vice-chair' },
  roleSECRETARY: { id: 'analytics.role.secretary', defaultMessage: 'Secretary' },
  roleMEMBER: { id: 'analytics.role.member', defaultMessage: 'Member' },
  roleSTANDING_INVITEE: { id: 'analytics.role.standingInvitee', defaultMessage: 'Standing invitee' },
  roleOBSERVER: { id: 'analytics.role.observer', defaultMessage: 'Observer' },
  seatIN_PERSON: { id: 'analytics.seat.inPerson', defaultMessage: 'Attended in person' },
  seatREMOTE: { id: 'analytics.seat.remote', defaultMessage: 'Attended remotely' },
  seatAPOLOGIES: { id: 'analytics.seat.apologies', defaultMessage: 'Apologies' },
  seatABSENT: { id: 'analytics.seat.absent', defaultMessage: 'Absent' },
  byAlternateWord: { id: 'analytics.seat.byAlternate', defaultMessage: 'represented by their alternate' },
  joinedLateWord: { id: 'analytics.seat.joinedLate', defaultMessage: 'joined late' },
  leftEarlyWord: { id: 'analytics.seat.leftEarly', defaultMessage: 'left early' },
})

/** Messages with values. */
export const amv = defineMessages<{
  showing: { from: string; to: string }
  summaryAttendance: { rate: string; meetings: number }
  summaryOverdue: { count: number }
  summaryMedian: { days: number }
  summary: { attendance: string; actions: string; timing: string }
  basis: { count: number }
  ofTotal: { part: number; total: number }
  overdueDetail: { over90: number; revised: number }
  closedDetail: { percent: string; agreed: number }
  atMeetings: { meetings: number }
  deferredDetail: { percent: string; twice: number }
  papersDetail: { percent: string }
  papersDetailLate: { percent: string; late: string }
  showingRole: { showing: string; role: string }
  byMemoType: { list: string }
  timingValue: { days: number }
  timingDetail: { p90: number; decisions: number }
  timingFew: { decisions: number }
  lateCounts: { items: number; papers: number }
  days: { days: number }
  quarter: { quarter: number; year: string }
  memoTypeTiming: { type: string; days: number; decisions: number }
  memoTypeFew: { type: string; decisions: number }
  timingSummary: { median: number; p90: number }
  deferralsSummary: { deferred: number; decisions: number; percent: string; twice: number }
  actionsSummary: { count: number; over90: number }
  closedSummary: { closed: number; original: number; agreed: number }
  papersSummary: { onTime: number; meetings: number }
  papersLate: { papers: number; items: number }
  meetingOf: { date: string }
  ownerHolder: { owner: string; holder: string }
  attendanceHeading: { committee: string }
  attendanceLead: { meetings: number; rate: string }
  ownRow: { forum: string; attended: number; expected: number; rate: string; overall: string }
  expectedFrom: { expected: number; from: string }
  apologiesAlt: { apologies: number; alternate: number }
  memberHeading: { name: string }
  memberLead: { attended: number; expected: number; rate: string }
  attendedOf: { attended: number; expected: number }
  seatWithNotes: { seat: string; notes: string }
}>({
  showing: { id: 'analytics.showing', defaultMessage: 'Showing {from} – {to}' },
  summaryAttendance: {
    id: 'analytics.summary.attendance',
    defaultMessage: '{rate} attendance at {meetings, plural, one {# meeting} other {# meetings}}',
  },
  summaryOverdue: {
    id: 'analytics.summary.overdue',
    defaultMessage: '{count, plural, =0 {no actions overdue} one {# action overdue} other {# actions overdue}}',
  },
  summaryMedian: {
    id: 'analytics.summary.median',
    defaultMessage: 'decisions took a median of {days, plural, one {# day} other {# days}}',
  },
  summary: { id: 'analytics.summary', defaultMessage: '{attendance} · {actions} · {timing}' },
  basis: {
    id: 'analytics.basis',
    defaultMessage:
      '{count, plural, =0 {No decision you can see in full in this period.} one {Based on # decision you can see in full.} other {Based on # decisions you can see in full.}}',
  },
  ofTotal: { id: 'analytics.ofTotal', defaultMessage: '{part} of {total}' },
  overdueDetail: {
    id: 'analytics.overdue.detail',
    defaultMessage:
      '{over90, plural, =0 {none over 90 days} other {# over 90 days}}; {revised, plural, =0 {no date changed} one {# with its date changed} other {# with their date changed}}',
  },
  closedDetail: {
    id: 'analytics.closed.detail',
    defaultMessage: '{percent} by the original date; {agreed} by the agreed date',
  },
  atMeetings: {
    id: 'analytics.attendance.atMeetings',
    defaultMessage: 'at {meetings, plural, one {# meeting} other {# meetings}}',
  },
  deferredDetail: {
    id: 'analytics.deferred.detail',
    defaultMessage:
      '{percent}; {twice, plural, =0 {no item deferred twice} one {# item deferred twice} other {# items deferred twice}}',
  },
  papersDetail: {
    id: 'analytics.papers.detail',
    defaultMessage: '{percent} of meetings published by the papers deadline',
  },
  papersDetailLate: {
    id: 'analytics.papers.detailLate',
    defaultMessage: '{percent} of meetings published by the papers deadline; {late}',
  },
  showingRole: { id: 'analytics.member.showingRole', defaultMessage: '{showing} · {role}' },
  byMemoType: { id: 'analytics.byMemoType', defaultMessage: 'By memo type: {list}' },
  timingValue: { id: 'analytics.timing.value', defaultMessage: '{days, plural, one {# day} other {# days}}' },
  timingDetail: {
    id: 'analytics.timing.detail',
    defaultMessage:
      'median; 9 in 10 within {p90, plural, one {# day} other {# days}} ({decisions, plural, one {# decision} other {# decisions}})',
  },
  timingFew: {
    id: 'analytics.timing.few',
    defaultMessage:
      'Too few decisions to show a typical time ({decisions, plural, one {# decision} other {# decisions}})',
  },
  lateCounts: {
    id: 'analytics.papers.lateCounts',
    defaultMessage:
      '{items, plural, one {# late item} other {# late items}}, {papers, plural, one {# late paper} other {# late papers}}',
  },
  days: { id: 'analytics.days', defaultMessage: '{days, plural, one {# day} other {# days}}' },
  quarter: { id: 'analytics.quarter', defaultMessage: 'Q{quarter} {year}' },
  memoTypeTiming: {
    id: 'analytics.memoType.timing',
    defaultMessage: '{type}: {days, plural, one {# day} other {# days}} ({decisions})',
  },
  memoTypeFew: {
    id: 'analytics.memoType.few',
    defaultMessage: '{type}: too few to show ({decisions})',
  },
  timingSummary: {
    id: 'analytics.timing.summary',
    defaultMessage:
      'Decisions took a median of {median, plural, one {# day} other {# days}}; 9 in 10 within {p90, plural, one {# day} other {# days}}.',
  },
  deferralsSummary: {
    id: 'analytics.deferrals.summary',
    defaultMessage:
      'Items deferred: {deferred} of {decisions} ({percent}); {twice, plural, =0 {no item deferred twice} one {# item deferred twice} other {# items deferred twice}}.',
  },
  actionsSummary: {
    id: 'analytics.actions.summary',
    defaultMessage:
      '{count, plural, one {# action is overdue} other {# actions are overdue}}; {over90, plural, =0 {none for over 90 days} other {# for over 90 days}}.',
  },
  closedSummary: {
    id: 'analytics.closed.summary',
    defaultMessage:
      '{closed, plural, =0 {No action was closed in the period.} one {# closed in the period: {original} by the original date, {agreed} by the agreed date.} other {# closed in the period: {original} by the original date, {agreed} by the agreed date.}}',
  },
  papersSummary: {
    id: 'analytics.papers.summary',
    defaultMessage:
      'Papers on time: {onTime} of {meetings, plural, one {# meeting} other {# meetings}} with a deadline.',
  },
  papersLate: {
    id: 'analytics.papers.lateSummary',
    defaultMessage:
      '{papers, plural, one {# late paper} other {# late papers}} and {items, plural, one {# late item} other {# late items}} in the period.',
  },
  meetingOf: { id: 'analytics.meetingOf', defaultMessage: 'Meeting of {date}' },
  ownerHolder: { id: 'analytics.ownerHolder', defaultMessage: '{owner} ({holder})' },
  attendanceHeading: { id: 'analytics.attendance.heading', defaultMessage: '{committee} — attendance' },
  attendanceLead: {
    id: 'analytics.attendance.lead',
    defaultMessage: '{meetings, plural, one {# meeting held} other {# meetings held}}. Overall attendance {rate}.',
  },
  ownRow: {
    id: 'analytics.attendance.ownRow',
    defaultMessage:
      'Your attendance: {attended} of {expected, plural, one {# meeting} other {# meetings}} ({rate}). The {forum}’s overall attendance: {overall}.',
  },
  expectedFrom: { id: 'analytics.attendance.expectedFrom', defaultMessage: '{expected} (from {from})' },
  apologiesAlt: { id: 'analytics.attendance.apologiesAlt', defaultMessage: '{apologies} ({alternate} by alternate)' },
  memberHeading: { id: 'analytics.member.heading', defaultMessage: '{name}' },
  memberLead: {
    id: 'analytics.member.lead',
    defaultMessage: 'Attended {attended} of {expected, plural, one {# meeting} other {# meetings}} ({rate}).',
  },
  attendedOf: { id: 'analytics.attendedOf', defaultMessage: '{attended} of {expected}' },
  seatWithNotes: { id: 'analytics.seatWithNotes', defaultMessage: '{seat} ({notes})' },
})

type TermMessage =
  | 'overviewTitle'
  | 'noScope'
  | 'notFound'
  | 'toOverview'
  | 'committee'
  | 'allCommittees'
  | 'linkDetail'
  | 'linkMeetings'
  | 'linkBackToDetail'
  | 'exportHint'

/** Texts that name the committee: they take the organisation's terms (ADR-0032). */
export const amt = defineMessages<Record<TermMessage, TermValues>>({
  overviewTitle: { id: 'analytics.overview.title', defaultMessage: '{Forum} analytics' },
  noScope: {
    id: 'analytics.noScope',
    defaultMessage: 'Analytics are for {forum} members, officers and oversight.',
  },
  notFound: {
    id: 'analytics.notFound',
    defaultMessage:
      '{forumGender, select, other {This {forum} is not in your analytics. Choose one from the overview.}}',
  },
  toOverview: { id: 'analytics.toOverview', defaultMessage: 'All {forum} analytics' },
  committee: { id: 'analytics.committee', defaultMessage: '{Forum}' },
  allCommittees: { id: 'analytics.allCommittees', defaultMessage: 'All my {forums}' },
  linkDetail: { id: 'analytics.link.detail', defaultMessage: '{Forum} detail' },
  linkMeetings: { id: 'analytics.link.meetings', defaultMessage: 'Meetings of this {forum}' },
  linkBackToDetail: { id: 'analytics.link.backToDetail', defaultMessage: 'Back to the {forum} detail' },
  exportHint: {
    id: 'analytics.attendance.exportHint',
    defaultMessage:
      'A spreadsheet of this table with the {forum}, the period and who produced it. Each export is recorded.',
  },
})
