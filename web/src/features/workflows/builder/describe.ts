import type { IntlShape } from 'react-intl'
import { bm, bmv, stepTypeLabels } from './builder-messages'
import { END, type AssigneeJson, type SlaJson, type StepJson } from './definition'

/** Names for the ids a body mentions (`VersionResponse.labels`), queue keys shown as they are. */
export type Labels = Record<string, string>

function named(intl: IntlShape, labels: Labels, id: string | undefined): string {
  if (!id) return intl.formatMessage(bmv.unknown, { id: '' })
  return labels[id] ?? intl.formatMessage(bmv.unknown, { id })
}

function unitName(intl: IntlShape, labels: Labels, unit: string | undefined): string {
  return !unit || unit === 'initiator' ? intl.formatMessage(bm.initiatorsUnit) : named(intl, labels, unit)
}

/** Who an assignee is, in words: "The holder of Head of Credit", "Credit officer in the initiator's unit or above". */
export function describeAssignee(intl: IntlShape, labels: Labels, a: AssigneeJson): string {
  switch (a.kind) {
    case 'person':
      return named(intl, labels, a.userId)
    case 'initiator':
      return intl.formatMessage(bm.initiator)
    case 'position':
      return intl.formatMessage(bmv.position, { name: named(intl, labels, a.positionId) })
    case 'supervisor':
      return intl.formatMessage(bmv.supervisor, { levels: a.levels ?? 1 })
    case 'unitHead':
      return intl.formatMessage(bmv.unitHead, { unit: unitName(intl, labels, a.unit), up: a.up ?? 0 })
    case 'role':
      return intl.formatMessage(bmv.role, { role: named(intl, labels, a.roleId), unit: unitName(intl, labels, a.unit) })
    case 'queue':
      return intl.formatMessage(bmv.queue, { queue: a.queue ?? '' })
  }
}

/** Who a step goes to (or, for a notify step, whom it tells); empty for steps without people. */
export function describeWho(intl: IntlShape, labels: Labels, step: StepJson): string {
  const list = (assignees: AssigneeJson[] | undefined) =>
    intl.formatList(
      (assignees ?? []).map((a) => describeAssignee(intl, labels, a)),
      { type: 'conjunction' },
    )
  switch (step.type) {
    case 'route':
      return intl.formatMessage(bm.routeChosen)
    case 'doa':
      return intl.formatMessage(bm.doaGroups)
    case 'parallel': {
      const mode =
        step.mode === 'any'
          ? intl.formatMessage(bm.parallelAny)
          : step.mode === 'n-of-m'
            ? intl.formatMessage(bmv.parallelN, { n: step.n ?? 1 })
            : intl.formatMessage(bm.parallelAll)
      return `${mode} ${list(step.assignees)}`
    }
    case 'notify':
      return list(step.recipients)
    case 'review':
    case 'approve':
    case 'sign':
      return step.assignee ? describeAssignee(intl, labels, step.assignee) : ''
    default:
      return ''
  }
}

/**
 * An ISO 8601 duration of days, hours and minutes (`P2D`, `PT4H`, `P1DT2H30M`) in words; in working time a day is a
 * working day (ADR-0041). Anything else is shown as written.
 */
export function describeDuration(intl: IntlShape, iso: string, working: boolean): string {
  const [date = '', time = ''] = iso.startsWith('P') ? iso.slice(1).split('T') : ['', '']
  const days = /^(\d+)D$/.exec(date)?.[1]
  const hoursPart = /^\d+H/.exec(time)?.[0] ?? ''
  const minutesPart = /^\d+M$/.exec(time.slice(hoursPart.length))?.[0] ?? ''
  const hours = hoursPart.slice(0, -1)
  const minutes = minutesPart.slice(0, -1)
  const wellFormed =
    (date === '' || days !== undefined) &&
    hoursPart.length + minutesPart.length === time.length &&
    `${date}${time}` !== ''
  if (!wellFormed) return iso
  const parts: string[] = []
  if (days) parts.push(intl.formatMessage(working ? bmv.workingDays : bmv.days, { n: Number(days) }))
  if (hours) parts.push(intl.formatMessage(working ? bmv.workingHours : bmv.hours, { n: Number(hours) }))
  if (minutes) parts.push(intl.formatMessage(working ? bmv.workingMinutes : bmv.minutes, { n: Number(minutes) }))
  return parts.length === 0 ? iso : intl.formatList(parts, { type: 'unit' })
}

/** A step's deadline in words: "Due in 2 working days; reminder 4 working hours before; the supervisor is told 1 working day late". */
export function describeSla(intl: IntlShape, labels: Labels, sla: SlaJson): string {
  const working = sla.workingTime !== false
  const d = (iso: string) => describeDuration(intl, iso, working)
  const parts = [intl.formatMessage(bmv.due, { duration: d(sla.due) })]
  if (sla.remind?.before) parts.push(intl.formatMessage(bmv.remindBefore, { duration: d(sla.remind.before) }))
  if (sla.remind?.after?.length) {
    parts.push(intl.formatMessage(bmv.remindAfter, { durations: intl.formatList(sla.remind.after.map(d)) }))
  }
  const e = sla.escalate
  if (e) {
    const after = d(e.after)
    parts.push(
      e.action === 'reassign'
        ? intl.formatMessage(bmv.escalateReassign, { after, to: e.to ? describeAssignee(intl, labels, e.to) : '' })
        : e.action === 'nextLevel'
          ? intl.formatMessage(bmv.escalateNextLevel, { after })
          : intl.formatMessage(bmv.escalateNotify, { after }),
    )
  }
  return parts.join('; ')
}

export function describeType(intl: IntlShape, step: StepJson): string {
  const label = (stepTypeLabels as Partial<Record<string, (typeof stepTypeLabels)['review']>>)[step.type]
  return label ? intl.formatMessage(label) : step.type
}

/** A step's name, or its id when it has none. */
export function stepName(step: StepJson): string {
  return step.name?.trim() ? step.name : step.id
}

/** Where a target leads, by name: a step's name, End, or the target as written when no step has it. */
export function targetName(intl: IntlShape, steps: StepJson[], target: string | undefined): string {
  if (!target) return intl.formatMessage(bm.nowhere)
  if (target === END) return intl.formatMessage(bm.end)
  const step = steps.find((s) => s.id === target)
  return step ? stepName(step) : intl.formatMessage(bmv.unknown, { id: target })
}
