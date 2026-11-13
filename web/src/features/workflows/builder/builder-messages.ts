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
})

export const bmv = defineMessages<{
  stepHeading: { index: number; name: string }
  edge: { from: string; to: string }
  edgeIf: { from: string; to: string; condition: string }
  edgeOtherwise: { from: string; to: string }
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
  edge: { id: 'workflows.builder.edge', defaultMessage: '{from} leads to {to}' },
  edgeIf: { id: 'workflows.builder.edge.if', defaultMessage: '{from} leads to {to} if {condition}' },
  edgeOtherwise: { id: 'workflows.builder.edge.otherwise', defaultMessage: '{from} otherwise leads to {to}' },
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
