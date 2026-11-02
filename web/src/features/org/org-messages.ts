import { defineMessages, type IntlShape } from 'react-intl'
import { isProblem } from '@/api/http'

/** Strings shared by the org screens (design E04 §13). */
export const om = defineMessages({
  title: { id: 'org.title', defaultMessage: 'Organisation' },
  lead: { id: 'org.lead', defaultMessage: 'Units, positions and who holds them.' },
  treeLabel: { id: 'org.tree', defaultMessage: 'Units' },
  unitPath: { id: 'org.unitPath', defaultMessage: 'Unit path' },
  filter: { id: 'org.filter', defaultMessage: 'Find a unit' },
  noMatch: { id: 'org.filter.none', defaultMessage: 'No unit matches.' },
  pickUnit: { id: 'org.pickUnit', defaultMessage: 'Choose a unit to see its positions.' },
  empty: { id: 'org.empty', defaultMessage: 'No units yet.' },
  emptyLead: { id: 'org.empty.lead', defaultMessage: 'Start with your legal entity and its top unit.' },
  code: { id: 'org.code', defaultMessage: 'Code' },
  name: { id: 'org.name', defaultMessage: 'Name' },
  positions: { id: 'org.positions', defaultMessage: 'Positions' },
  positionsCaption: { id: 'org.positions.caption', defaultMessage: 'Positions of the unit' },
  noPositions: { id: 'org.positions.none', defaultMessage: 'No positions in this unit.' },
  colPosition: { id: 'org.col.position', defaultMessage: 'Position' },
  colHolder: { id: 'org.col.holder', defaultMessage: 'Held by' },
  colSince: { id: 'org.col.since', defaultMessage: 'Since' },
  head: { id: 'org.head', defaultMessage: 'Head' },
  vacant: { id: 'org.vacant', defaultMessage: 'Vacant' },
  addUnit: { id: 'org.addUnit', defaultMessage: 'Add sub-unit' },
  addRoot: { id: 'org.addRoot', defaultMessage: 'Add top unit' },
  addEntity: { id: 'org.addEntity', defaultMessage: 'Add legal entity' },
  entityName: { id: 'org.entityName', defaultMessage: 'Legal entity name' },
  addPosition: { id: 'org.addPosition', defaultMessage: 'Add position' },
  rename: { id: 'org.rename', defaultMessage: 'Rename' },
  move: { id: 'org.move', defaultMessage: 'Move' },
  close: { id: 'org.close', defaultMessage: 'Close' },
  edit: { id: 'org.edit', defaultMessage: 'Edit' },
  save: { id: 'org.save', defaultMessage: 'Save' },
  cancel: { id: 'org.cancel', defaultMessage: 'Cancel' },
  unitTitle: { id: 'org.unitDialog', defaultMessage: 'Unit' },
  newTopUnit: { id: 'org.newTopUnit', defaultMessage: 'New top unit' },
  moveLead: { id: 'org.moveLead', defaultMessage: 'Its sub-units and positions move with it.' },
  newParent: { id: 'org.newParent', defaultMessage: 'New parent unit' },
  closeLead: {
    id: 'org.closeLead',
    defaultMessage: 'It stays in the history. Give the first day it no longer exists.',
  },
  closeDate: { id: 'org.closeDate', defaultMessage: 'Closes on' },
  positionTitle: { id: 'org.positionTitle', defaultMessage: 'Title' },
  positionCode: { id: 'org.positionCode', defaultMessage: 'Code (optional)' },
  headOfUnit: { id: 'org.headOfUnit', defaultMessage: 'Head of the unit' },
  editPosition: { id: 'org.editPosition', defaultMessage: 'Edit position' },
  holders: { id: 'org.holders', defaultMessage: 'Held by, over time' },
  holdersCaption: { id: 'org.holders.caption', defaultMessage: 'Who held this position' },
  noHolders: { id: 'org.holders.none', defaultMessage: 'Nobody has held this position yet.' },
  colPerson: { id: 'org.col.person', defaultMessage: 'Person' },
  colFrom: { id: 'org.col.from', defaultMessage: 'From' },
  colUntil: { id: 'org.col.until', defaultMessage: 'Until' },
  colStatus: { id: 'org.col.status', defaultMessage: 'Status' },
  colActions: { id: 'org.col.actions', defaultMessage: 'Actions' },
  ongoing: { id: 'org.ongoing', defaultMessage: 'Ongoing' },
  cancelled: { id: 'org.cancelled', defaultMessage: 'Cancelled' },
  current: { id: 'org.current', defaultMessage: 'Current' },
  past: { id: 'org.past', defaultMessage: 'Past' },
  upcoming: { id: 'org.upcoming', defaultMessage: 'Upcoming' },
  assign: { id: 'org.assign', defaultMessage: 'Assign person' },
  person: { id: 'org.person', defaultMessage: 'Person' },
  fromDate: { id: 'org.fromDate', defaultMessage: 'From' },
  untilDate: { id: 'org.untilDate', defaultMessage: 'Until (optional)' },
  end: { id: 'org.end', defaultMessage: 'End' },
  endDate: { id: 'org.endDate', defaultMessage: 'First day without the position' },
  cancelAssignment: { id: 'org.cancelAssignment', defaultMessage: 'Cancel record' },
  cancelAssignmentTitle: { id: 'org.cancelAssignmentTitle', defaultMessage: 'Cancel this record?' },
  cancelAssignmentLead: {
    id: 'org.cancelAssignmentLead',
    defaultMessage: 'Use this for an assignment entered by mistake. It stays visible as cancelled.',
  },
  keep: { id: 'org.keep', defaultMessage: 'Keep' },
  reportsTo: { id: 'org.reportsTo', defaultMessage: 'Reports to' },
  reportsToNone: { id: 'org.reportsTo.none', defaultMessage: 'Reports to nobody' },
  changeLine: { id: 'org.changeLine', defaultMessage: 'Change reporting line' },
  linePosition: { id: 'org.linePosition', defaultMessage: 'Position' },
  history: { id: 'org.history', defaultMessage: 'Change history' },
  historyCaption: { id: 'org.history.caption', defaultMessage: 'Changes, newest first' },
  historyEmpty: { id: 'org.history.empty', defaultMessage: 'No changes recorded.' },
  system: { id: 'org.history.system', defaultMessage: 'System' },
  colWhen: { id: 'org.col.when', defaultMessage: 'When' },
  colChange: { id: 'org.col.change', defaultMessage: 'Change' },
  colBy: { id: 'org.col.by', defaultMessage: 'By' },
  saved: { id: 'org.saved', defaultMessage: 'Saved.' },
  required: { id: 'org.required', defaultMessage: 'Required.' },
  unit: { id: 'org.unit', defaultMessage: 'Unit' },
  failed: { id: 'org.failed', defaultMessage: 'That did not work. Try again.' },
  changed: { id: 'org.refused.changed', defaultMessage: 'Someone changed this meanwhile. It was reloaded.' },
  forbidden: { id: 'org.refused.forbidden', defaultMessage: 'You cannot change this part of the organisation.' },
  OVERLAP: { id: 'org.refused.overlap', defaultMessage: 'Someone else holds this position in that period.' },
  USER_OVERLAP: { id: 'org.refused.userOverlap', defaultMessage: 'This person holds another position in that period.' },
  USER_INACTIVE: { id: 'org.refused.userInactive', defaultMessage: 'This person’s account is not active.' },
  DATES: { id: 'org.refused.dates', defaultMessage: 'Those dates do not fit.' },
  CYCLE: { id: 'org.refused.cycle', defaultMessage: 'That would make it its own parent or manager.' },
  NOT_EMPTY: { id: 'org.refused.notEmpty', defaultMessage: 'Close or end what is still inside it first.' },
  DUPLICATE_CODE: { id: 'org.refused.duplicateCode', defaultMessage: 'Another open unit or position has this code.' },
  HEAD_TAKEN: { id: 'org.refused.headTaken', defaultMessage: 'The unit already has a head position.' },
  CLOSED: { id: 'org.refused.closed', defaultMessage: 'It is closed on that date.' },
  OTHER_ENTITY: { id: 'org.refused.otherEntity', defaultMessage: 'Units can only move within their legal entity.' },
  CANCELLED: { id: 'org.refused.cancelled', defaultMessage: 'A cancelled record cannot change.' },
})

/** Strings with values, typed so every placeholder gets one. */
export const omv = defineMessages<{
  since: { date: string }
  closedOn: { date: string }
  newUnitUnder: { parent: string }
  moveTitle: { name: string }
  closeTitle: { name: string }
  newPosition: { unit: string }
  assignTitle: { position: string }
  endTitle: { name: string }
  lineTitle: { position: string }
  backToUnit: { unit: string }
  unitWithCode: { name: string; code: string }
}>({
  since: { id: 'org.since', defaultMessage: 'Since {date}' },
  closedOn: { id: 'org.closedOn', defaultMessage: 'Closed on {date}' },
  newUnitUnder: { id: 'org.newUnitUnder', defaultMessage: 'New unit under {parent}' },
  moveTitle: { id: 'org.moveTitle', defaultMessage: 'Move {name}' },
  closeTitle: { id: 'org.closeTitle', defaultMessage: 'Close {name}' },
  newPosition: { id: 'org.newPosition', defaultMessage: 'New position in {unit}' },
  assignTitle: { id: 'org.assignTitle', defaultMessage: 'Assign a person to {position}' },
  endTitle: { id: 'org.endTitle', defaultMessage: 'End {name}’s assignment' },
  lineTitle: { id: 'org.lineTitle', defaultMessage: 'Who does {position} report to?' },
  backToUnit: { id: 'org.backToUnit', defaultMessage: 'Back to {unit}' },
  unitWithCode: { id: 'org.unitWithCode', defaultMessage: '{name} ({code})' },
})

const REASONS = [
  'OVERLAP',
  'USER_OVERLAP',
  'USER_INACTIVE',
  'DATES',
  'CYCLE',
  'NOT_EMPTY',
  'DUPLICATE_CODE',
  'HEAD_TAKEN',
  'CLOSED',
  'OTHER_ENTITY',
  'CANCELLED',
] as const

/** The message for a failed org change: a refusal reason, a lost race, missing access, or a generic failure. */
export function orgProblemMessage(intl: IntlShape, error: unknown): string {
  if (!isProblem(error)) return intl.formatMessage(om.failed)
  const reason = REASONS.find((r) => r === error.reason)
  if (reason) return intl.formatMessage(om[reason])
  if (error.status === 412) return intl.formatMessage(om.changed)
  if (error.status === 403) return intl.formatMessage(om.forbidden)
  if (error.status === 400 && error.detail) return error.detail
  return intl.formatMessage(om.failed)
}

/** Today in the browser, as `YYYY-MM-DD` for date fields (the server checks dates in the organisation's time zone). */
export function today(): string {
  const d = new Date()
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
