import { defineMessages } from 'react-intl'
import type { StepType } from './definition'

/** Texts of the workflow builder (design E06); the `workflows.` ids load with the admin catalogue part. */
export const bm = defineMessages({
  viewDiagram: { id: 'workflows.builder.view.diagram', defaultMessage: 'Diagram' },
  viewSteps: { id: 'workflows.builder.view.steps', defaultMessage: 'Steps' },
  viewJson: { id: 'workflows.builder.view.json', defaultMessage: 'JSON' },
  views: { id: 'workflows.builder.views', defaultMessage: 'Views of this version' },
  diagramLabel: { id: 'workflows.builder.diagram', defaultMessage: 'Workflow diagram' },
  diagramHint: {
    id: 'workflows.builder.diagram.hint',
    defaultMessage: 'The Steps view shows the same workflow as a list, for keyboard and screen reader use.',
  },
  stepsLabel: { id: 'workflows.builder.steps', defaultMessage: 'Steps, in the order they are written' },
  notReadable: {
    id: 'workflows.builder.notReadable',
    defaultMessage:
      'This body cannot be drawn: it needs a start and a list of steps, each with an id and a type. Use the JSON view.',
  },
  start: { id: 'workflows.builder.start', defaultMessage: 'Start' },
  end: { id: 'workflows.builder.end', defaultMessage: 'End' },
  goesTo: { id: 'workflows.builder.goesTo', defaultMessage: 'Goes to' },
  tells: { id: 'workflows.builder.tells', defaultMessage: 'Tells' },
  deadline: { id: 'workflows.builder.deadline', defaultMessage: 'Deadline' },
  leadsTo: { id: 'workflows.builder.leadsTo', defaultMessage: 'Leads to' },
  runs: { id: 'workflows.builder.runs', defaultMessage: 'Runs' },
  forum: { id: 'workflows.builder.forum', defaultMessage: 'Forum' },
  otherwise: { id: 'workflows.builder.otherwise', defaultMessage: 'Otherwise' },
  nowhere: { id: 'workflows.builder.nowhere', defaultMessage: 'Nowhere yet' },
  initiator: { id: 'workflows.builder.assignee.initiator', defaultMessage: 'The initiator' },
  initiatorsUnit: { id: 'workflows.builder.assignee.initiatorsUnit', defaultMessage: 'the initiator’s unit' },
  routeChosen: { id: 'workflows.builder.route', defaultMessage: 'The approvers the initiator chooses' },
  doaGroups: { id: 'workflows.builder.doa', defaultMessage: 'The approval groups of the delegation of authority' },
  parallelAll: { id: 'workflows.builder.parallel.all', defaultMessage: 'All of' },
  parallelAny: { id: 'workflows.builder.parallel.any', defaultMessage: 'Any one of' },
  edit: { id: 'workflows.builder.edit', defaultMessage: 'Change' },
  doneEditing: { id: 'workflows.builder.doneEditing', defaultMessage: 'Close' },
  stepName: { id: 'workflows.builder.field.name', defaultMessage: 'Name' },
  addAfter: { id: 'workflows.builder.addAfter', defaultMessage: 'Add a step after this one' },
  addFirst: { id: 'workflows.builder.addFirst', defaultMessage: 'Add a step at the start' },
  stepType: { id: 'workflows.builder.field.type', defaultMessage: 'Type of step' },
  add: { id: 'workflows.builder.add', defaultMessage: 'Add' },
  remove: { id: 'workflows.builder.remove', defaultMessage: 'Remove this step' },
  removeConfirm: { id: 'workflows.builder.remove.confirm', defaultMessage: 'Remove' },
  cancel: { id: 'workflows.builder.cancel', defaultMessage: 'Cancel' },
  condition: { id: 'workflows.builder.field.condition', defaultMessage: 'Condition' },
  addCondition: { id: 'workflows.builder.addCondition', defaultMessage: 'Add a condition' },
  undo: { id: 'workflows.builder.undo', defaultMessage: 'Undo' },
  redo: { id: 'workflows.builder.redo', defaultMessage: 'Redo' },
  editTools: { id: 'workflows.builder.tools', defaultMessage: 'Change the workflow' },
  selected: { id: 'workflows.builder.selected', defaultMessage: 'Selected step' },
  noSelection: {
    id: 'workflows.builder.noSelection',
    defaultMessage: 'Select a step in the diagram to change it, or use the Steps view.',
  },
  checking: { id: 'workflows.builder.checking', defaultMessage: 'Checking…' },
  conflict: {
    id: 'workflows.builder.conflict',
    defaultMessage:
      'Someone else saved this draft while you were changing it. Your changes are still here: copy them, or reload the saved version.',
  },
  reload: { id: 'workflows.builder.reload', defaultMessage: 'Reload the saved version' },
  copy: { id: 'workflows.builder.copy', defaultMessage: 'Copy my changes as JSON' },
  copied: { id: 'workflows.builder.copied', defaultMessage: 'Copied.' },
})

export const bmv = defineMessages<{
  stepHeading: { index: number; name: string }
  removeTitle: { name: string }
  checked: { count: number }
  conditionN: { n: number }
  leadsToN: { n: number }
  removeCondition: { n: number }
  stepProblems: { name: string }
  stepAria: { index: number; count: number; type: string; name: string; who: string; next: string }
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
  stepHeading: { id: 'workflows.builder.stepHeading', defaultMessage: '{index}. {name}' },
  removeTitle: { id: 'workflows.builder.remove.title', defaultMessage: 'Remove “{name}”?' },
  checked: {
    id: 'workflows.builder.checked',
    defaultMessage: '{count, plural, =0 {No problems found.} one {# problem found.} other {# problems found.}}',
  },
  conditionN: { id: 'workflows.builder.conditionN', defaultMessage: 'Condition {n}' },
  leadsToN: { id: 'workflows.builder.leadsToN', defaultMessage: 'If condition {n} holds, leads to' },
  removeCondition: { id: 'workflows.builder.removeCondition', defaultMessage: 'Remove condition {n}' },
  stepProblems: { id: 'workflows.builder.stepProblems', defaultMessage: 'Problems in {name}' },
  stepAria: {
    id: 'workflows.builder.stepAria',
    defaultMessage: 'Step {index} of {count}, {type}, {name}. {who} Leads to {next}.',
  },
  problems: {
    id: 'workflows.builder.problems',
    defaultMessage: '{count, plural, one {# problem} other {# problems}}',
  },
  unknown: { id: 'workflows.builder.unknown', defaultMessage: 'Unknown ({id})' },
  position: { id: 'workflows.builder.assignee.position', defaultMessage: 'The holder of {name}' },
  supervisor: {
    id: 'workflows.builder.assignee.supervisor',
    defaultMessage:
      '{levels, plural, one {The initiator’s supervisor} other {The initiator’s supervisor, # levels up}}',
  },
  unitHead: {
    id: 'workflows.builder.assignee.unitHead',
    defaultMessage:
      '{up, plural, =0 {The head of {unit}} one {The head of the unit above {unit}} other {The head of the unit # levels above {unit}}}',
  },
  role: { id: 'workflows.builder.assignee.role', defaultMessage: '{role} in {unit} or above' },
  queue: { id: 'workflows.builder.assignee.queue', defaultMessage: 'The queue {queue}' },
  parallelN: { id: 'workflows.builder.parallel.n', defaultMessage: '{n} of' },
  ifCondition: { id: 'workflows.builder.if', defaultMessage: 'If {condition}' },
  days: { id: 'workflows.builder.duration.days', defaultMessage: '{n, plural, one {# day} other {# days}}' },
  workingDays: {
    id: 'workflows.builder.duration.workingDays',
    defaultMessage: '{n, plural, one {# working day} other {# working days}}',
  },
  hours: { id: 'workflows.builder.duration.hours', defaultMessage: '{n, plural, one {# hour} other {# hours}}' },
  workingHours: {
    id: 'workflows.builder.duration.workingHours',
    defaultMessage: '{n, plural, one {# working hour} other {# working hours}}',
  },
  minutes: {
    id: 'workflows.builder.duration.minutes',
    defaultMessage: '{n, plural, one {# minute} other {# minutes}}',
  },
  workingMinutes: {
    id: 'workflows.builder.duration.workingMinutes',
    defaultMessage: '{n, plural, one {# working minute} other {# working minutes}}',
  },
  due: { id: 'workflows.builder.sla.due', defaultMessage: 'Due in {duration}' },
  remindBefore: { id: 'workflows.builder.sla.remindBefore', defaultMessage: 'reminder {duration} before' },
  remindAfter: { id: 'workflows.builder.sla.remindAfter', defaultMessage: 'reminders {durations} after' },
  escalateNotify: {
    id: 'workflows.builder.sla.escalate.notify',
    defaultMessage: 'the supervisor is told {after} late',
  },
  escalateReassign: {
    id: 'workflows.builder.sla.escalate.reassign',
    defaultMessage: 'moves to {to} {after} late',
  },
  escalateNextLevel: {
    id: 'workflows.builder.sla.escalate.nextLevel',
    defaultMessage: 'moves to the next level {after} late',
  },
})

export const stepTypeLabels = defineMessages<StepType>({
  route: { id: 'workflows.builder.type.route', defaultMessage: 'Route' },
  review: { id: 'workflows.builder.type.review', defaultMessage: 'Review' },
  approve: { id: 'workflows.builder.type.approve', defaultMessage: 'Approval' },
  parallel: { id: 'workflows.builder.type.parallel', defaultMessage: 'Parallel decision' },
  doa: { id: 'workflows.builder.type.doa', defaultMessage: 'Delegation of authority' },
  branch: { id: 'workflows.builder.type.branch', defaultMessage: 'Branch' },
  notify: { id: 'workflows.builder.type.notify', defaultMessage: 'Notify' },
  subworkflow: { id: 'workflows.builder.type.subworkflow', defaultMessage: 'Sub-workflow' },
  'escalate-to-forum': { id: 'workflows.builder.type.forum', defaultMessage: 'Escalate to a forum' },
  sign: { id: 'workflows.builder.type.sign', defaultMessage: 'Signature' },
})
