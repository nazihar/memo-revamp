import { defineMessages } from 'react-intl'

/**
 * Texts of the register of declarations across committees (Phase 4 E04 T8, flow U): `/declarations`, its filters, the
 * table, a row's details and the Excel export. In the `declarations` catalogue part, which the route loads.
 */
export const gm = defineMessages({
  title: { id: 'declarations.register.title', defaultMessage: 'Register of declarations of interest' },
  lead: {
    id: 'declarations.register.lead',
    defaultMessage:
      'Declarations of interest in the committees you oversee, newest first. A row’s details say how it was made and decided.',
  },
  filters: { id: 'declarations.register.filters', defaultMessage: 'Filters' },
  committee: { id: 'declarations.register.committee', defaultMessage: 'Committee' },
  allCommittees: { id: 'declarations.register.allCommittees', defaultMessage: 'All' },
  from: { id: 'declarations.register.from', defaultMessage: 'From' },
  to: { id: 'declarations.register.to', defaultMessage: 'To' },
  member: { id: 'declarations.register.member', defaultMessage: 'Member' },
  anyMember: { id: 'declarations.register.anyMember', defaultMessage: 'Any' },
  memberHint: {
    id: 'declarations.register.memberHint',
    defaultMessage: 'Choose a committee to pick one of its members.',
  },
  state: { id: 'declarations.register.state', defaultMessage: 'State' },
  anyState: { id: 'declarations.register.anyState', defaultMessage: 'Any' },
  export: { id: 'declarations.register.export', defaultMessage: 'Export to Excel' },
  exportHint: {
    id: 'declarations.register.exportHint',
    defaultMessage: 'The export holds the rows under these filters, at most 20,000. It is recorded in the audit log.',
  },
  exported: { id: 'declarations.register.exported', defaultMessage: 'The workbook has been downloaded.' },
  tooMany: {
    id: 'declarations.register.tooMany',
    defaultMessage: 'You have exported this register several times in the last hour. Try again later.',
  },
  exportFailed: {
    id: 'declarations.register.exportFailed',
    defaultMessage: 'The export did not work. Try again in a moment.',
  },
  caption: { id: 'declarations.register.caption', defaultMessage: 'Declarations of interest, newest first' },
  colMeeting: { id: 'declarations.register.col.meeting', defaultMessage: 'Meeting' },
  colItem: { id: 'declarations.register.col.item', defaultMessage: 'Item' },
  colMember: { id: 'declarations.register.col.member', defaultMessage: 'Member' },
  colWhat: { id: 'declarations.register.col.what', defaultMessage: 'What' },
  colInterest: { id: 'declarations.register.col.interest', defaultMessage: 'Interest' },
  colDecision: { id: 'declarations.register.col.decision', defaultMessage: 'Decision' },
  restrictedInterest: { id: 'declarations.register.restrictedInterest', defaultMessage: '(restricted item)' },
  noDecision: { id: 'declarations.register.noDecision', defaultMessage: '—' },
  details: { id: 'declarations.register.details', defaultMessage: 'Details' },
  atMeeting: { id: 'declarations.register.atMeeting', defaultMessage: 'Declared during the meeting.' },
  empty: { id: 'declarations.register.empty', defaultMessage: 'No declarations match these filters.' },
  loadMore: { id: 'declarations.register.loadMore', defaultMessage: 'Load more' },
  forbidden: {
    id: 'declarations.register.forbidden',
    defaultMessage:
      'This register is for auditors and administrators, and for the secretaries and chairs of each committee.',
  },
  notFound: {
    id: 'declarations.register.notFound',
    defaultMessage: 'You cannot read the declarations of that committee. Choose another one.',
  },
})

export const gmv = defineMessages<{
  details: { member: string; item: string }
  meetingOf: { committee: string }
}>({
  details: { id: 'declarations.register.detailsNamed', defaultMessage: 'Details: {member}, item {item}' },
  meetingOf: { id: 'declarations.register.meetingOf', defaultMessage: '{committee} meeting' },
})
