import { defineMessages } from 'react-intl'
import type { StepType } from './definition'

/** Texts of the workflow builder (design E06); the `workflows.` ids load with the admin catalogue part. */
export const bm = defineMessages({
  viewDiagram: { id: 'builder.view.diagram', defaultMessage: 'Diagram' },
  viewSteps: { id: 'builder.view.steps', defaultMessage: 'Steps' },
  viewJson: { id: 'builder.view.json', defaultMessage: 'JSON' },
  views: { id: 'builder.views', defaultMessage: 'Views of this version' },
  diagramLabel: { id: 'builder.diagram', defaultMessage: 'Workflow diagram' },
  diagramHint: {
    id: 'builder.diagram.hint',
    defaultMessage: 'The Steps view shows the same workflow as a list, for keyboard and screen reader use.',
  },
  stepsLabel: { id: 'builder.steps', defaultMessage: 'Steps, in the order they are written' },
  notReadable: {
    id: 'builder.notReadable',
    defaultMessage:
      'This body cannot be drawn: it needs a start and a list of steps, each with an id and a type. Use the JSON view.',
  },
  start: { id: 'builder.start', defaultMessage: 'Start' },
  end: { id: 'builder.end', defaultMessage: 'End' },
  goesTo: { id: 'builder.goesTo', defaultMessage: 'Goes to' },
  tells: { id: 'builder.tells', defaultMessage: 'Tells' },
  deadline: { id: 'builder.deadline', defaultMessage: 'Deadline' },
  leadsTo: { id: 'builder.leadsTo', defaultMessage: 'Leads to' },
  runs: { id: 'builder.runs', defaultMessage: 'Runs' },
  forum: { id: 'builder.forum', defaultMessage: 'Forum' },
  otherwise: { id: 'builder.otherwise', defaultMessage: 'Otherwise' },
  nowhere: { id: 'builder.nowhere', defaultMessage: 'Nowhere yet' },
  initiator: { id: 'builder.assignee.initiator', defaultMessage: 'The initiator' },
  initiatorsUnit: { id: 'builder.assignee.initiatorsUnit', defaultMessage: 'the initiator’s unit' },
  routeChosen: { id: 'builder.route', defaultMessage: 'The approvers the initiator chooses' },
  doaGroups: { id: 'builder.doa', defaultMessage: 'The approval groups of the delegation of authority' },
  parallelAll: { id: 'builder.parallel.all', defaultMessage: 'All of' },
  parallelAny: { id: 'builder.parallel.any', defaultMessage: 'Any one of' },
  edit: { id: 'builder.edit', defaultMessage: 'Change' },
  doneEditing: { id: 'builder.doneEditing', defaultMessage: 'Close' },
  stepName: { id: 'builder.field.name', defaultMessage: 'Name' },
  addAfter: { id: 'builder.addAfter', defaultMessage: 'Add a step after this one' },
  addFirst: { id: 'builder.addFirst', defaultMessage: 'Add a step at the start' },
  stepType: { id: 'builder.field.type', defaultMessage: 'Type of step' },
  add: { id: 'builder.add', defaultMessage: 'Add' },
  remove: { id: 'builder.remove', defaultMessage: 'Remove this step' },
  removeConfirm: { id: 'builder.remove.confirm', defaultMessage: 'Remove' },
  cancel: { id: 'builder.cancel', defaultMessage: 'Cancel' },
  condition: { id: 'builder.field.condition', defaultMessage: 'Condition' },
  addCondition: { id: 'builder.addCondition', defaultMessage: 'Add a condition' },
  undo: { id: 'builder.undo', defaultMessage: 'Undo' },
  redo: { id: 'builder.redo', defaultMessage: 'Redo' },
  editTools: { id: 'builder.tools', defaultMessage: 'Change the workflow' },
  selected: { id: 'builder.selected', defaultMessage: 'Selected step' },
  noSelection: {
    id: 'builder.noSelection',
    defaultMessage: 'Select a step in the diagram to change it, or use the Steps view.',
  },
  checking: { id: 'builder.checking', defaultMessage: 'Checking…' },
  conflict: {
    id: 'builder.conflict',
    defaultMessage:
      'Someone else saved this draft while you were changing it. Your changes are still here: copy them, or reload the saved version.',
  },
  reload: { id: 'builder.reload', defaultMessage: 'Reload the saved version' },
  copy: { id: 'builder.copy', defaultMessage: 'Copy my changes as JSON' },
  copied: { id: 'builder.copied', defaultMessage: 'Copied.' },
  reached: { id: 'builder.reached', defaultMessage: 'Reached in the simulation' },
  announceConnected: { id: 'builder.announce.connected', defaultMessage: 'Connection changed.' },
  announceUndone: { id: 'builder.announce.undone', defaultMessage: 'Undone.' },
  announceRedone: { id: 'builder.announce.redone', defaultMessage: 'Redone.' },
  added: { id: 'builder.added', defaultMessage: 'Added' },
  removed: { id: 'builder.removed', defaultMessage: 'Removed' },
  changed: { id: 'builder.changed', defaultMessage: 'Changed' },
  viewChanges: { id: 'builder.view.changes', defaultMessage: 'Changes' },
})

export const bmv = defineMessages<{
  stepHeading: { index: number; name: string }
  removeTitle: { name: string }
  checked: { count: number }
  conditionN: { n: number }
  leadsToN: { n: number }
  removeCondition: { n: number }
  stepProblems: { name: string }
  edge: { from: string; to: string }
  edgeIf: { from: string; to: string; condition: string }
  edgeOtherwise: { from: string; to: string }
  stepAria: { index: number; count: number; type: string; name: string; who: string; next: string }
  stepAriaReached: { label: string }
  announceAdded: { name: string }
  announceRemoved: { name: string }
  stepAriaAdded: { label: string }
  stepAriaChanged: { label: string }
  problems: { count: number }
  unknown: { id: string }
  position: { name: string }
  supervisor: { levels: number }
  unitHead: { unit: string; up: number }
  role: { role: string; unit: string }
  queue: { queue: string }
  parallelN: { n: number }
  ifCondition: { condition: string }
  days: { n: number }
  workingDays: { n: number }
  hours: { n: number }
  workingHours: { n: number }
  minutes: { n: number }
  workingMinutes: { n: number }
  due: { duration: string }
  remindBefore: { duration: string }
  remindAfter: { durations: string }
  escalateNotify: { after: string }
  escalateReassign: { after: string; to: string }
  escalateNextLevel: { after: string }
}>({
  stepHeading: { id: 'builder.stepHeading', defaultMessage: '{index}. {name}' },
  removeTitle: { id: 'builder.remove.title', defaultMessage: 'Remove “{name}”?' },
  checked: {
    id: 'builder.checked',
    defaultMessage: '{count, plural, =0 {No problems found.} one {# problem found.} other {# problems found.}}',
  },
  conditionN: { id: 'builder.conditionN', defaultMessage: 'Condition {n}' },
  leadsToN: { id: 'builder.leadsToN', defaultMessage: 'If condition {n} holds, leads to' },
  removeCondition: { id: 'builder.removeCondition', defaultMessage: 'Remove condition {n}' },
  stepProblems: { id: 'builder.stepProblems', defaultMessage: 'Problems in {name}' },
  edge: { id: 'builder.edge', defaultMessage: '{from} leads to {to}' },
  edgeIf: { id: 'builder.edge.if', defaultMessage: '{from} leads to {to} if {condition}' },
  edgeOtherwise: { id: 'builder.edge.otherwise', defaultMessage: '{from} otherwise leads to {to}' },
  stepAria: {
    id: 'builder.stepAria',
    defaultMessage: 'Step {index} of {count}, {type}, {name}. {who} Leads to {next}.',
  },
  stepAriaReached: { id: 'builder.stepAria.reached', defaultMessage: '{label} Reached in the simulation.' },
  announceAdded: { id: 'builder.announce.added', defaultMessage: '{name} added.' },
  announceRemoved: { id: 'builder.announce.removed', defaultMessage: '{name} removed.' },
  stepAriaAdded: { id: 'builder.stepAria.added', defaultMessage: '{label} Added in this version.' },
  stepAriaChanged: { id: 'builder.stepAria.changed', defaultMessage: '{label} Changed in this version.' },
  problems: {
    id: 'builder.problems',
    defaultMessage: '{count, plural, one {# problem} other {# problems}}',
  },
  unknown: { id: 'builder.unknown', defaultMessage: 'Unknown ({id})' },
  position: { id: 'builder.assignee.position', defaultMessage: 'The holder of {name}' },
  supervisor: {
    id: 'builder.assignee.supervisor',
    defaultMessage:
      '{levels, plural, one {The initiator’s supervisor} other {The initiator’s supervisor, # levels up}}',
  },
  unitHead: {
    id: 'builder.assignee.unitHead',
    defaultMessage:
      '{up, plural, =0 {The head of {unit}} one {The head of the unit above {unit}} other {The head of the unit # levels above {unit}}}',
  },
  role: { id: 'builder.assignee.role', defaultMessage: '{role} in {unit} or above' },
  queue: { id: 'builder.assignee.queue', defaultMessage: 'The queue {queue}' },
  parallelN: { id: 'builder.parallel.n', defaultMessage: '{n} of' },
  ifCondition: { id: 'builder.if', defaultMessage: 'If {condition}' },
  days: { id: 'builder.duration.days', defaultMessage: '{n, plural, one {# day} other {# days}}' },
  workingDays: {
    id: 'builder.duration.workingDays',
    defaultMessage: '{n, plural, one {# working day} other {# working days}}',
  },
  hours: { id: 'builder.duration.hours', defaultMessage: '{n, plural, one {# hour} other {# hours}}' },
  workingHours: {
    id: 'builder.duration.workingHours',
    defaultMessage: '{n, plural, one {# working hour} other {# working hours}}',
  },
  minutes: {
    id: 'builder.duration.minutes',
    defaultMessage: '{n, plural, one {# minute} other {# minutes}}',
  },
  workingMinutes: {
    id: 'builder.duration.workingMinutes',
    defaultMessage: '{n, plural, one {# working minute} other {# working minutes}}',
  },
  due: { id: 'builder.sla.due', defaultMessage: 'Due in {duration}' },
  remindBefore: { id: 'builder.sla.remindBefore', defaultMessage: 'reminder {duration} before' },
  remindAfter: { id: 'builder.sla.remindAfter', defaultMessage: 'reminders {durations} after' },
  escalateNotify: {
    id: 'builder.sla.escalate.notify',
    defaultMessage: 'the supervisor is told {after} late',
  },
  escalateReassign: {
    id: 'builder.sla.escalate.reassign',
    defaultMessage: 'moves to {to} {after} late',
  },
  escalateNextLevel: {
    id: 'builder.sla.escalate.nextLevel',
    defaultMessage: 'moves to the next level {after} late',
  },
})

export const stepTypeLabels = defineMessages<StepType>({
  route: { id: 'builder.type.route', defaultMessage: 'Route' },
  review: { id: 'builder.type.review', defaultMessage: 'Review' },
  approve: { id: 'builder.type.approve', defaultMessage: 'Approval' },
  parallel: { id: 'builder.type.parallel', defaultMessage: 'Parallel decision' },
  doa: { id: 'builder.type.doa', defaultMessage: 'Delegation of authority' },
  branch: { id: 'builder.type.branch', defaultMessage: 'Branch' },
  notify: { id: 'builder.type.notify', defaultMessage: 'Notify' },
  subworkflow: { id: 'builder.type.subworkflow', defaultMessage: 'Sub-workflow' },
  'escalate-to-forum': { id: 'builder.type.forum', defaultMessage: 'Escalate to a forum' },
  sign: { id: 'builder.type.sign', defaultMessage: 'Signature' },
})

/** What a step lets its holder do (design E03 §4.4), by the definition's action name. */
export const actionLabels = defineMessages({
  APPROVE: { id: 'builder.action.approve', defaultMessage: 'Approve' },
  RECOMMEND: { id: 'builder.action.recommend', defaultMessage: 'Recommend' },
  RETURN: { id: 'builder.action.return', defaultMessage: 'Return to the initiator' },
  REJECT: { id: 'builder.action.reject', defaultMessage: 'Reject' },
  SEND_BACK: { id: 'builder.action.sendBack', defaultMessage: 'Send back to an earlier step' },
  FORWARD: { id: 'builder.action.forward', defaultMessage: 'Forward' },
  DELEGATE: { id: 'builder.action.delegate', defaultMessage: 'Delegate' },
  ASK: { id: 'builder.action.ask', defaultMessage: 'Ask for opinions' },
  REFER: { id: 'builder.action.refer', defaultMessage: 'Refer' },
})
