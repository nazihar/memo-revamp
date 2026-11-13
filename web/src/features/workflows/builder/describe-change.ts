import { defineMessages, type IntlShape } from 'react-intl'
import { actionLabels, bm, bmv } from './builder-messages'
import type { AssigneeJson, SlaJson, StepJson } from './definition'
import { describeAssignee, describeDuration, describeSla, targetName, type Labels } from './describe'
import type { Change } from './diff'

export const cdm = defineMessages({
  none: { id: 'builder.change.none', defaultMessage: 'none' },
  yes: { id: 'builder.change.yes', defaultMessage: 'yes' },
  no: { id: 'builder.change.no', defaultMessage: 'no' },
  name: { id: 'builder.change.field.name', defaultMessage: 'Name' },
  type: { id: 'builder.change.field.type', defaultMessage: 'Type' },
  start: { id: 'builder.change.field.start', defaultMessage: 'Starts at' },
  attributes: { id: 'builder.change.field.attributes', defaultMessage: 'Attributes conditions may read' },
  resubmit: { id: 'builder.change.field.resubmit', defaultMessage: 'When resubmitted' },
  actions: { id: 'builder.change.field.actions', defaultMessage: 'What the holder can do' },
  remarks: { id: 'builder.change.field.remarks', defaultMessage: 'Actions that need a remark' },
  forward: { id: 'builder.change.field.forward', defaultMessage: 'Forwarding' },
  mode: { id: 'builder.change.field.mode', defaultMessage: 'Decided when' },
  n: { id: 'builder.change.field.n', defaultMessage: 'How many' },
  message: { id: 'builder.change.field.message', defaultMessage: 'Message' },
  due: { id: 'builder.change.field.due', defaultMessage: 'Due after' },
  workingTime: { id: 'builder.change.field.workingTime', defaultMessage: 'Working time only' },
  remindBefore: { id: 'builder.change.field.remindBefore', defaultMessage: 'Reminder before' },
  remindAfter: { id: 'builder.change.field.remindAfter', defaultMessage: 'Reminders after' },
  escalate: { id: 'builder.change.field.escalate', defaultMessage: 'When it is late' },
  fallback: { id: 'builder.change.field.fallback', defaultMessage: 'If nobody is found' },
})

const cdv = defineMessages<{ branchLine: { condition: string; to: string } }>({
  branchLine: { id: 'builder.change.branch', defaultMessage: 'if {condition}, leads to {to}' },
})

const DURATIONS = new Set(['sla.due', 'sla.remind.before', 'sla.escalate.after'])

/** A change as words: which property, and what it was and is (design E06 §6). */
export interface ChangeWords {
  field: string
  before: string
  after: string
}

function fieldName(intl: IntlShape, path: string): string {
  const branch = /^branches\[(\d+)\](?:\.(when|next))?$/.exec(path)
  if (branch) {
    const n = Number(branch[1]) + 1
    return branch[2] === 'next' ? intl.formatMessage(bmv.leadsToN, { n }) : intl.formatMessage(bmv.conditionN, { n })
  }
  const fields: Record<string, string> = {
    name: intl.formatMessage(cdm.name),
    type: intl.formatMessage(cdm.type),
    start: intl.formatMessage(cdm.start),
    attributes: intl.formatMessage(cdm.attributes),
    resubmit: intl.formatMessage(cdm.resubmit),
    next: intl.formatMessage(bm.leadsTo),
    otherwise: intl.formatMessage(bm.otherwise),
    assignee: intl.formatMessage(bm.goesTo),
    assignees: intl.formatMessage(bm.goesTo),
    recipients: intl.formatMessage(bm.tells),
    actions: intl.formatMessage(cdm.actions),
    remarks: intl.formatMessage(cdm.remarks),
    forward: intl.formatMessage(cdm.forward),
    'forward.within': intl.formatMessage(cdm.forward),
    mode: intl.formatMessage(cdm.mode),
    n: intl.formatMessage(cdm.n),
    message: intl.formatMessage(cdm.message),
    definition: intl.formatMessage(bm.runs),
    forum: intl.formatMessage(bm.forum),
    sla: intl.formatMessage(bm.deadline),
    'sla.due': intl.formatMessage(cdm.due),
    'sla.workingTime': intl.formatMessage(cdm.workingTime),
    'sla.remind': intl.formatMessage(cdm.remindBefore),
    'sla.remind.before': intl.formatMessage(cdm.remindBefore),
    'sla.remind.after': intl.formatMessage(cdm.remindAfter),
    'sla.escalate': intl.formatMessage(cdm.escalate),
    fallback: intl.formatMessage(cdm.fallback),
  }
  return fields[path] ?? path
}

function valueText(intl: IntlShape, labels: Labels, steps: StepJson[], path: string, value: unknown): string {
  if (value === undefined || value === null) return intl.formatMessage(cdm.none)
  if (path === 'next' || path === 'otherwise' || path === 'start' || /^branches\[\d+\]\.next$/.test(path)) {
    return targetName(intl, steps, typeof value === 'string' ? value : undefined)
  }
  if (/^branches\[\d+\]$/.test(path)) {
    const b = value as { when: string; next: string }
    return intl.formatMessage(cdv.branchLine, { condition: b.when, to: targetName(intl, steps, b.next) })
  }
  if (path === 'assignee' || path === 'fallback' || path === 'sla.escalate.to') {
    return describeAssignee(intl, labels, value as AssigneeJson)
  }
  if (path === 'assignees' || path === 'recipients') {
    return intl.formatList((value as AssigneeJson[]).map((a) => describeAssignee(intl, labels, a)))
  }
  if (path === 'sla') return describeSla(intl, labels, value as SlaJson)
  if (path === 'sla.escalate') {
    const e = value as NonNullable<SlaJson['escalate']>
    const after = describeDuration(intl, e.after, true)
    if (e.action === 'reassign') {
      return intl.formatMessage(bmv.escalateReassign, { after, to: e.to ? describeAssignee(intl, labels, e.to) : '' })
    }
    return intl.formatMessage(e.action === 'nextLevel' ? bmv.escalateNextLevel : bmv.escalateNotify, { after })
  }
  if (DURATIONS.has(path) && typeof value === 'string') return describeDuration(intl, value, true)
  if (path === 'sla.remind.after' && Array.isArray(value)) {
    return intl.formatList(value.map((d) => describeDuration(intl, String(d), true)))
  }
  if ((path === 'actions' || path === 'remarks') && Array.isArray(value)) {
    return intl.formatList(
      value.map((a) => {
        const key = String(a)
        return key in actionLabels ? intl.formatMessage(actionLabels[key as keyof typeof actionLabels]) : key
      }),
    )
  }
  if (typeof value === 'boolean') return intl.formatMessage(value ? cdm.yes : cdm.no)
  if (typeof value === 'string' || typeof value === 'number') return String(value)
  return JSON.stringify(value)
}

/**
 * [change] of a step (or of the body, for top-level paths) in words. What it was is named from the older version's
 * steps ([before]), what it is from the newer one's ([after]).
 */
export function describeChange(
  intl: IntlShape,
  labels: Labels,
  before: StepJson[],
  after: StepJson[],
  change: Change,
): ChangeWords {
  return {
    field: fieldName(intl, change.path),
    before: valueText(intl, labels, before, change.path, change.before),
    after: valueText(intl, labels, after, change.path, change.after),
  }
}
