import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of the committees, their members and "My committees" (Phase 4 E01 T5, flows K and L). The `forums` prefix
 * loads with `/committees` and `/me/committees` (catalogue part `forums`), which members open as well as
 * administrators. Messages that name the committee take the organisation's terms (ADR-0032): `{forum}`, `{forums}`,
 * `{Forum}`, `{Forums}` and `{forumGender}`.
 */
export const fm = defineMessages({
  colName: { id: 'forums.col.name', defaultMessage: 'Name' },
  colCode: { id: 'forums.col.code', defaultMessage: 'Code' },
  colType: { id: 'forums.col.type', defaultMessage: 'Type' },
  colChair: { id: 'forums.col.chair', defaultMessage: 'Chair' },
  colMembers: { id: 'forums.col.members', defaultMessage: 'Members' },
  colStatus: { id: 'forums.col.status', defaultMessage: 'Status' },
  colRole: { id: 'forums.col.role', defaultMessage: 'Role' },
  colVotes: { id: 'forums.col.votes', defaultMessage: 'Votes' },
  colAttributes: { id: 'forums.col.attributes', defaultMessage: 'Attributes' },
  colAlternate: { id: 'forums.col.alternate', defaultMessage: 'Alternate' },
  colTerm: { id: 'forums.col.term', defaultMessage: 'Term' },
  colActions: { id: 'forums.col.actions', defaultMessage: 'Actions' },
  colFrom: { id: 'forums.col.from', defaultMessage: 'From' },
  colTo: { id: 'forums.col.to', defaultMessage: 'Until' },
  colState: { id: 'forums.col.state', defaultMessage: 'Now' },
  colAuthority: { id: 'forums.col.authority', defaultMessage: 'Authority' },
  ACTIVE: { id: 'forums.status.active', defaultMessage: 'Active' },
  DISSOLVED: { id: 'forums.status.dissolved', defaultMessage: 'Dissolved' },
  none: { id: 'forums.none', defaultMessage: 'None' },
  yes: { id: 'forums.yes', defaultMessage: 'Yes' },
  no: { id: 'forums.no', defaultMessage: 'No' },
  vacant: { id: 'forums.vacant', defaultMessage: 'Vacant' },
  exOfficio: { id: 'forums.exOfficio', defaultMessage: 'Ex officio' },
  openEnded: { id: 'forums.openEnded', defaultMessage: 'No end date' },
  noTermMonths: { id: 'forums.policy.noTermMonths', defaultMessage: 'Terms have no usual length.' },
  // Roles
  CHAIR: { id: 'forums.role.chair', defaultMessage: 'Chair' },
  VICE_CHAIR: { id: 'forums.role.viceChair', defaultMessage: 'Vice-chair' },
  SECRETARY: { id: 'forums.role.secretary', defaultMessage: 'Secretary' },
  MEMBER: { id: 'forums.role.member', defaultMessage: 'Member' },
  STANDING_INVITEE: { id: 'forums.role.standingInvitee', defaultMessage: 'Standing invitee' },
  OBSERVER: { id: 'forums.role.observer', defaultMessage: 'Observer' },
  // Where a row stands
  UPCOMING: { id: 'forums.state.upcoming', defaultMessage: 'Starts later' },
  IN_OFFICE: { id: 'forums.state.inOffice', defaultMessage: 'In office' },
  TERM_ENDED: { id: 'forums.state.termEnded', defaultMessage: 'Term ended, not renewed' },
  ENDED: { id: 'forums.state.ended', defaultMessage: 'Ended' },
  // Why a row ended
  RESIGNED: { id: 'forums.reason.resigned', defaultMessage: 'Resigned' },
  TERM_COMPLETED: { id: 'forums.reason.termCompleted', defaultMessage: 'Term completed' },
  REMOVED: { id: 'forums.reason.removed', defaultMessage: 'Removed' },
  RENEWED: { id: 'forums.reason.renewed', defaultMessage: 'Renewed' },
  ROLE_CHANGED: { id: 'forums.reason.roleChanged', defaultMessage: 'Role changed' },
  WITHDRAWN: { id: 'forums.reason.withdrawn', defaultMessage: 'Withdrawn before starting' },
  OTHER: { id: 'forums.reason.other', defaultMessage: 'Other' },
  // New committee, edit, clone
  type: { id: 'forums.type', defaultMessage: 'Type' },
  chooseType: { id: 'forums.type.choose', defaultMessage: 'Choose a type…' },
  name: { id: 'forums.name', defaultMessage: 'Name' },
  shortName: { id: 'forums.shortName', defaultMessage: 'Short name' },
  shortNameHint: { id: 'forums.shortName.hint', defaultMessage: 'Used where space is short, such as on a phone.' },
  code: { id: 'forums.code', defaultMessage: 'Code' },
  codeHint: {
    id: 'forums.code.hint',
    defaultMessage: 'Up to 10 capital letters, digits or hyphens. Meeting and resolution numbers use it.',
  },
  codeFormat: { id: 'forums.code.format', defaultMessage: 'Use 1 to 10 capital letters, digits or hyphens.' },
  key: { id: 'forums.key', defaultMessage: 'Workflow key' },
  keyFormat: {
    id: 'forums.key.format',
    defaultMessage: 'Start with a small letter; then small letters, digits or hyphens.',
  },
  reportsTo: { id: 'forums.reportsTo', defaultMessage: 'Reports to' },
  reportsToNone: { id: 'forums.reportsTo.none', defaultMessage: 'No one' },
  classification: { id: 'forums.classification', defaultMessage: 'Classification' },
  classificationDefault: { id: 'forums.classification.default', defaultMessage: 'As the type says' },
  typeInactive: { id: 'forums.type.inactive', defaultMessage: 'This type is switched off or not yet published.' },
  create: { id: 'forums.create', defaultMessage: 'Create' },
  save: { id: 'forums.save', defaultMessage: 'Save' },
  cancel: { id: 'forums.cancel', defaultMessage: 'Cancel' },
  required: { id: 'forums.required', defaultMessage: 'Required.' },
  optional: { id: 'forums.optional', defaultMessage: '(optional)' },
  failed: { id: 'forums.failed', defaultMessage: 'That did not work. Try again.' },
  saved: { id: 'forums.saved', defaultMessage: 'Saved.' },
  conflict: {
    id: 'forums.conflict',
    defaultMessage: 'Someone else changed this in the meantime. It has been reloaded; try again.',
  },
  copyMembers: { id: 'forums.clone.copyMembers', defaultMessage: 'Copy the current members' },
  copyFrom: { id: 'forums.clone.copyFrom', defaultMessage: 'Their membership starts on' },
  copyHint: { id: 'forums.clone.copyHint', defaultMessage: 'Each starts a first term of the usual length.' },
  cloneSubmit: { id: 'forums.clone.submit', defaultMessage: 'Clone' },
  dissolvedOn: { id: 'forums.dissolve.on', defaultMessage: 'Dissolved on' },
  dissolveReason: { id: 'forums.dissolve.reason', defaultMessage: 'Reason' },
  dissolveSubmit: { id: 'forums.dissolve.submit', defaultMessage: 'Dissolve' },
  // Committee page
  addMember: { id: 'forums.addMember', defaultMessage: 'Add member' },
  more: { id: 'forums.more', defaultMessage: 'More actions' },
  clone: { id: 'forums.action.clone', defaultMessage: 'Clone' },
  edit: { id: 'forums.action.edit', defaultMessage: 'Edit' },
  dissolve: { id: 'forums.action.dissolve', defaultMessage: 'Dissolve' },
  tabMembers: { id: 'forums.tab.members', defaultMessage: 'Members' },
  tabHistory: { id: 'forums.tab.history', defaultMessage: 'History' },
  tabPolicy: { id: 'forums.tab.policy', defaultMessage: 'Policy' },
  noSecretary: { id: 'forums.noSecretary', defaultMessage: 'Secretary: none yet' },
  membersOn: { id: 'forums.membersOn', defaultMessage: 'Members on' },
  historyLead: {
    id: 'forums.history.lead',
    defaultMessage:
      'Every term of office, newest first. Renewing a term or changing a role adds a row; ending a membership keeps its row. Nothing is deleted.',
  },
  // Member row actions
  renew: { id: 'forums.member.renew', defaultMessage: 'Renew term' },
  changeRole: { id: 'forums.member.changeRole', defaultMessage: 'Change role' },
  editMember: { id: 'forums.member.edit', defaultMessage: 'Edit' },
  end: { id: 'forums.member.end', defaultMessage: 'End membership' },
  withdraw: { id: 'forums.member.withdraw', defaultMessage: 'Withdraw' },
  // Add or edit a member
  who: { id: 'forums.member.who', defaultMessage: 'Who' },
  whoPerson: { id: 'forums.member.who.person', defaultMessage: 'A person' },
  whoPosition: { id: 'forums.member.who.position', defaultMessage: 'A position, whoever holds it' },
  person: { id: 'forums.member.person', defaultMessage: 'Person' },
  position: { id: 'forums.member.position', defaultMessage: 'Position' },
  positionHint: {
    id: 'forums.member.position.hint',
    defaultMessage: 'For example, the Chief Risk Officer. The seat passes to whoever holds the position.',
  },
  pickPerson: { id: 'forums.member.pickPerson', defaultMessage: 'Choose a person from the list.' },
  pickPosition: { id: 'forums.member.pickPosition', defaultMessage: 'Choose a position from the list.' },
  role: { id: 'forums.member.role', defaultMessage: 'Role' },
  secretaryHint: {
    id: 'forums.member.secretaryHint',
    defaultMessage: 'Only an administrator can appoint a chair or a secretary.',
  },
  votes: { id: 'forums.member.votes', defaultMessage: 'Votes' },
  votesHint: {
    id: 'forums.member.votes.hint',
    defaultMessage: 'Set from the role. Change it if the charter says otherwise.',
  },
  attributes: { id: 'forums.member.attributes', defaultMessage: 'Attributes' },
  attributesHint: { id: 'forums.member.attributes.hint', defaultMessage: 'Used in the quorum rule.' },
  alternate: { id: 'forums.member.alternate', defaultMessage: 'Alternate' },
  alternateHint: {
    id: 'forums.member.alternate.hint',
    defaultMessage: 'Attends and counts when the member sends apologies.',
  },
  from: { id: 'forums.member.from', defaultMessage: 'From' },
  termEnds: { id: 'forums.member.termEnds', defaultMessage: 'Term ends' },
  termEndsOpenHint: { id: 'forums.member.termEnds.open', defaultMessage: 'Leave it empty for no planned end.' },
  authority: { id: 'forums.member.authority', defaultMessage: 'Authority' },
  authorityHint: {
    id: 'forums.member.authority.hint',
    defaultMessage: 'The decision that appointed the member, for example a Board resolution of 12 October 2026.',
  },
  newRole: { id: 'forums.member.newRole', defaultMessage: 'New role' },
  newRoleFrom: { id: 'forums.member.newRoleFrom', defaultMessage: 'First day in the new role' },
  changeRoleLead: {
    id: 'forums.member.changeRole.lead',
    defaultMessage: 'The current row ends the day before and a new row starts in the new role. The term continues.',
  },
  changeRoleSubmit: { id: 'forums.member.changeRole.submit', defaultMessage: 'Change role' },
  renewEnds: { id: 'forums.member.renew.ends', defaultMessage: 'New term ends' },
  renewSubmit: { id: 'forums.member.renew.submit', defaultMessage: 'Renew' },
  lastDay: { id: 'forums.member.lastDay', defaultMessage: 'Last day' },
  reason: { id: 'forums.member.reason', defaultMessage: 'Reason' },
  note: { id: 'forums.member.note', defaultMessage: 'Details' },
  noteHint: { id: 'forums.member.note.hint', defaultMessage: 'Required when the reason is Other.' },
  endLead: {
    id: 'forums.member.end.lead',
    defaultMessage: 'The membership stays in the history with its dates and reason.',
  },
  endSubmit: { id: 'forums.member.end.submit', defaultMessage: 'End membership' },
  withdrawLead: {
    id: 'forums.member.withdraw.lead',
    defaultMessage: 'This membership has not started. It stays in the history, marked as withdrawn.',
  },
  // Refusals
  SECRETARY_LIMIT: {
    id: 'forums.problem.secretaryLimit',
    defaultMessage: 'Only an administrator can change a chair, a secretary or your own membership.',
  },
  MEMBER_ENDED: {
    id: 'forums.problem.memberEnded',
    defaultMessage: 'This membership has ended. Add a new one instead.',
  },
  NO_TERM: {
    id: 'forums.problem.noTerm',
    defaultMessage: 'This membership has no term end to renew from. Edit it to set one first.',
  },
  SAME_ROLE: { id: 'forums.problem.sameRole', defaultMessage: 'The member already has this role.' },
  TOO_EARLY: {
    id: 'forums.problem.tooEarly',
    defaultMessage: 'The new role must start after the membership starts. Withdraw it and add it again instead.',
  },
  NOT_UPCOMING: {
    id: 'forums.problem.notUpcoming',
    defaultMessage: 'Only a membership that has not started can be withdrawn.',
  },
  KEY_FIXED: { id: 'forums.problem.keyFixed', defaultMessage: 'The workflow key never changes.' },
  SELF: { id: 'forums.problem.self', defaultMessage: 'A member cannot be their own alternate.' },
  BEFORE_START: { id: 'forums.problem.beforeStart', defaultMessage: 'The end cannot be before the start.' },
  UNKNOWN_ATTRIBUTE: { id: 'forums.problem.unknownAttribute', defaultMessage: 'Not an attribute of this type.' },
  unknownPerson: { id: 'forums.problem.unknownPerson', defaultMessage: 'That person or position cannot be added.' },
  // My committees
  open: { id: 'forums.mine.open', defaultMessage: 'Open' },
  votesYes: { id: 'forums.mine.votes', defaultMessage: 'votes' },
  votesNo: { id: 'forums.mine.noVote', defaultMessage: 'does not vote' },
  formerOnly: {
    id: 'forums.mine.former',
    defaultMessage: 'You no longer sit on it, so only your own membership is shown.',
  },
})

/** Texts with values besides the terms. */
export const fmv = defineMessages<{
  nameWithCode: { name: string; code: string }
  reportsTo: { name: string }
  secretaries: { count: number; names: string }
  dissolved: { date: string; reason: string }
  membersCaption: { date: string }
  historyCaption: { name: string }
  quorumToday: { needed: number; basis: number; who: string }
  quorumOn: { needed: number; basis: number; who: string; date: string }
  noMembersOn: { date: string }
  warnings: { count: number }
  seatHolder: { name: string }
  term: { n: number; date: string }
  termOpen: { n: number }
  endsIn: { days: number }
  planned: { date: string }
  endedBecause: { state: string; reason: string }
  rowActions: { name: string }
  addTitle: { name: string }
  editTitle: { name: string }
  renewTitle: { name: string }
  renewLead: { n: number; date: string }
  renewBeyond: { n: number; max: number }
  renewedBeyond: { max: number }
  changeRoleTitle: { name: string }
  endTitle: { name: string }
  withdrawTitle: { name: string }
  cloneTitle: { name: string }
  editForumTitle: { name: string }
  dissolveTitle: { name: string }
  termFromType: { months: number }
  fixedValues: { key: string; type: string }
  policyOf: { name: string; version: number }
  termMonths: { months: number }
  maxTerms: { max: number }
  reminders: { days: string }
  outcome: { label: string; meaning: string }
  mineRole: { role: string; votes: string }
  mineTermEnds: { n: number; date: string }
  mineStarts: { date: string }
  mineEnded: { date: string }
  mineChair: { name: string }
  mineMembers: { count: number }
  openNamed: { name: string }
}>({
  nameWithCode: { id: 'forums.nameWithCode', defaultMessage: '{name} ({code})' },
  reportsTo: { id: 'forums.reportsTo.named', defaultMessage: 'Reports to: {name}' },
  secretaries: {
    id: 'forums.secretaries',
    defaultMessage: '{count, plural, one {Secretary} other {Secretaries}}: {names}',
  },
  dissolved: { id: 'forums.dissolved', defaultMessage: 'Dissolved on {date}: {reason}' },
  membersCaption: { id: 'forums.members.caption', defaultMessage: 'Members on {date}' },
  historyCaption: { id: 'forums.history.caption', defaultMessage: 'Every term of office of {name}' },
  quorumToday: {
    id: 'forums.quorum.today',
    defaultMessage:
      '{who, select, seats {Quorum today: {needed} of {basis} seats} all {Quorum today: {needed} of {basis} members} other {Quorum today: {needed} of {basis} voting members}}',
  },
  quorumOn: {
    id: 'forums.quorum.on',
    defaultMessage:
      '{who, select, seats {Quorum on {date}: {needed} of {basis} seats} all {Quorum on {date}: {needed} of {basis} members} other {Quorum on {date}: {needed} of {basis} voting members}}',
  },
  noMembersOn: { id: 'forums.members.none', defaultMessage: 'Nobody is in office on {date}.' },
  warnings: {
    id: 'forums.warnings',
    defaultMessage: '{count, plural, one {# term ends soon or has ended} other {# terms end soon or have ended}}',
  },
  seatHolder: { id: 'forums.seatHolder', defaultMessage: 'Now: {name}' },
  term: {
    id: 'forums.term',
    defaultMessage: '{n, selectordinal, one {#st} two {#nd} few {#rd} other {#th}} term, to {date}',
  },
  termOpen: {
    id: 'forums.term.open',
    defaultMessage: '{n, selectordinal, one {#st} two {#nd} few {#rd} other {#th}} term',
  },
  endsIn: {
    id: 'forums.term.endsIn',
    defaultMessage: '{days, plural, =0 {Ends today} one {Ends in # day} other {Ends in # days}}',
  },
  planned: { id: 'forums.term.planned', defaultMessage: '{date} (planned)' },
  endedBecause: { id: 'forums.endedBecause', defaultMessage: '{state}: {reason}' },
  rowActions: { id: 'forums.member.actions', defaultMessage: 'Actions for {name}' },
  addTitle: { id: 'forums.member.add.title', defaultMessage: 'Add a member to {name}' },
  editTitle: { id: 'forums.member.edit.title', defaultMessage: 'Edit the membership of {name}' },
  renewTitle: { id: 'forums.member.renew.title', defaultMessage: 'Renew the term of {name}' },
  renewLead: { id: 'forums.member.renew.lead', defaultMessage: 'Term {n} starts on {date}.' },
  renewBeyond: {
    id: 'forums.member.renew.beyond',
    defaultMessage:
      'This will be term {n}. The type allows at most {max} consecutive terms. You can still renew if the charter allows it.',
  },
  renewedBeyond: {
    id: 'forums.member.renew.beyondDone',
    defaultMessage: 'Renewed beyond the usual limit of {max} consecutive terms.',
  },
  changeRoleTitle: { id: 'forums.member.changeRole.title', defaultMessage: 'Change the role of {name}' },
  endTitle: { id: 'forums.member.end.title', defaultMessage: 'End the membership of {name}' },
  withdrawTitle: { id: 'forums.member.withdraw.title', defaultMessage: 'Withdraw the membership of {name}?' },
  cloneTitle: { id: 'forums.clone.title', defaultMessage: 'Clone {name}' },
  editForumTitle: { id: 'forums.edit.title', defaultMessage: 'Edit {name}' },
  dissolveTitle: { id: 'forums.dissolve.title', defaultMessage: 'Dissolve {name}?' },
  termFromType: {
    id: 'forums.member.termFromType',
    defaultMessage: '{months, plural, one {# month, as the type says.} other {# months, as the type says.}}',
  },
  fixedValues: { id: 'forums.fixedValues', defaultMessage: 'These never change: workflow key {key}, type {type}.' },
  policyOf: {
    id: 'forums.policy.of',
    defaultMessage: 'The policy of the type “{name}”, version {version}. It is shown here to read only.',
  },
  termMonths: {
    id: 'forums.policy.termMonths',
    defaultMessage: '{months, plural, one {A term usually lasts # month.} other {A term usually lasts # months.}}',
  },
  maxTerms: {
    id: 'forums.policy.maxTerms',
    defaultMessage:
      '{max, plural, one {At most # term in a row; renewing beyond it warns, never blocks.} other {At most # terms in a row; renewing beyond it warns, never blocks.}}',
  },
  reminders: {
    id: 'forums.policy.reminders',
    defaultMessage: 'The secretary is reminded {days} days before a term ends.',
  },
  outcome: { id: 'forums.policy.outcome', defaultMessage: '{label} ({meaning})' },
  mineRole: { id: 'forums.mine.role', defaultMessage: '{role} · {votes}' },
  mineTermEnds: { id: 'forums.mine.termEnds', defaultMessage: 'Term {n} ends on {date}' },
  mineStarts: { id: 'forums.mine.starts', defaultMessage: 'Starts on {date}' },
  mineEnded: { id: 'forums.mine.ended', defaultMessage: 'Ended on {date}' },
  mineChair: { id: 'forums.mine.chair', defaultMessage: 'Chair: {name}' },
  mineMembers: { id: 'forums.mine.members', defaultMessage: '{count, plural, one {# member} other {# members}}' },
  openNamed: { id: 'forums.mine.openNamed', defaultMessage: 'Open {name}' },
})

type TermMessage =
  | 'title'
  | 'lead'
  | 'add'
  | 'caption'
  | 'empty'
  | 'back'
  | 'tabs'
  | 'keyHint'
  | 'keyTaken'
  | 'codeTaken'
  | 'parentCycle'
  | 'cloneLead'
  | 'dissolveLead'
  | 'policyLink'
  | 'ONE_CHAIR'
  | 'ALREADY_MEMBER'
  | 'FORUM_DISSOLVED'
  | 'mineTitle'
  | 'mineLead'
  | 'mineEmpty'

/** Texts that name the committee: they take the organisation's terms (ADR-0032). */
export const fmt = defineMessages<Record<TermMessage, TermValues>>({
  title: { id: 'forums.title', defaultMessage: '{Forums}' },
  lead: {
    id: 'forums.lead',
    defaultMessage:
      'Each {forum} you may see, with its chair and how many members it has today. Sitting on a {forum} is what lets you see it.',
  },
  add: { id: 'forums.add', defaultMessage: 'New {forum}' },
  caption: { id: 'forums.caption', defaultMessage: '{Forums} you may see' },
  empty: {
    id: 'forums.empty',
    defaultMessage: 'There is nothing to show yet. A {forum} appears here when you sit on it.',
  },
  back: { id: 'forums.back', defaultMessage: 'All {forums}' },
  tabs: { id: 'forums.tabs', defaultMessage: 'Parts of the {forum} page' },
  keyHint: {
    id: 'forums.key.hint',
    defaultMessage: 'Workflow steps name the {forum} by this key. It is suggested from the name and never changes.',
  },
  keyTaken: { id: 'forums.key.taken', defaultMessage: 'Another {forum} has this key.' },
  codeTaken: { id: 'forums.code.taken', defaultMessage: 'Another {forum} has this code.' },
  parentCycle: {
    id: 'forums.parent.cycle',
    defaultMessage: '{forumGender, select, other {A {forum} cannot report to itself or to one that reports to it.}}',
  },
  cloneLead: {
    id: 'forums.clone.lead',
    defaultMessage: 'A new {forum} with the same type, classification and reporting line.',
  },
  dissolveLead: {
    id: 'forums.dissolve.lead',
    defaultMessage:
      'The {forum} stops taking members and items. Its members and history stay. There is no way back; clone it to start again.',
  },
  policyLink: { id: 'forums.policy.link', defaultMessage: 'Open the {forum} type' },
  ONE_CHAIR: {
    id: 'forums.problem.oneChair',
    defaultMessage: 'The {forum} already has a chair for part of that time. End or change the current chair first.',
  },
  ALREADY_MEMBER: {
    id: 'forums.problem.alreadyMember',
    defaultMessage: 'This person or position already sits on the {forum} for part of that time.',
  },
  FORUM_DISSOLVED: {
    id: 'forums.problem.dissolved',
    defaultMessage: 'The {forum} is dissolved. Members can only be ended.',
  },
  mineTitle: { id: 'forums.mine.title', defaultMessage: 'My {forums}' },
  mineLead: {
    id: 'forums.mine.lead',
    defaultMessage: 'Each {forum} you sit on, will sit on or sat on, with your role and term.',
  },
  mineEmpty: { id: 'forums.mine.empty', defaultMessage: 'You do not sit on any {forum}.' },
})
