import { END, type BranchJson, type DefinitionBody, type StepJson, type StepType } from './definition'
import { START_NODE } from './model'

/**
 * Where a connection starts (design E06 §3): the definition's `start`, a step's `next`, a branch's condition `i`, or its
 * `otherwise`.
 */
export type Slot =
  | { kind: 'start' }
  | { kind: 'next'; step: string }
  | { kind: 'branch'; step: string; index: number }
  | { kind: 'otherwise'; step: string }

/** Every change the builder makes to a body; each one is undoable (the page keeps the bodies, `History`). */
export type Command =
  | { kind: 'add'; after: string; step: StepJson }
  | { kind: 'connect'; from: Slot; to: string }
  | { kind: 'remove'; id: string }
  | { kind: 'set'; id: string; patch: Partial<StepJson> }
  | { kind: 'addBranch'; id: string; branch: BranchJson }
  | { kind: 'removeBranch'; id: string; index: number }

function withSteps(body: DefinitionBody, steps: StepJson[]): DefinitionBody {
  return { ...body, steps }
}

function mapStep(body: DefinitionBody, id: string, change: (step: StepJson) => StepJson): DefinitionBody {
  return withSteps(
    body,
    body.steps.map((s) => (s.id === id ? change(s) : s)),
  )
}

/** [patch] merged into [step]; a key set to undefined is removed, so the body never carries `"key": null`. */
export function patchStep(step: StepJson, patch: Partial<StepJson>): StepJson {
  const next: Record<string, unknown> = { ...step }
  for (const [key, value] of Object.entries(patch)) {
    if (value === undefined) Reflect.deleteProperty(next, key)
    else next[key] = value
  }
  return next as StepJson
}

/** Where a connection out of [slot] leads now. */
export function targetOf(body: DefinitionBody, slot: Slot): string | undefined {
  if (slot.kind === 'start') return body.start
  const step = body.steps.find((s) => s.id === slot.step)
  if (!step) return undefined
  if (slot.kind === 'next') return step.next
  if (slot.kind === 'otherwise') return step.otherwise
  return step.branches?.[slot.index]?.next
}

function connect(body: DefinitionBody, from: Slot, to: string): DefinitionBody {
  if (from.kind === 'start') return { ...body, start: to }
  return mapStep(body, from.step, (step) => {
    if (from.kind === 'next') return { ...step, next: to }
    if (from.kind === 'otherwise') return { ...step, otherwise: to }
    const branches = (step.branches ?? []).map((b, i) => (i === from.index ? { ...b, next: to } : b))
    return { ...step, branches }
  })
}

/** Every connection in the body that leads to [id], with the slot it starts from. */
export function incoming(body: DefinitionBody, id: string): Slot[] {
  const slots: Slot[] = body.start === id ? [{ kind: 'start' }] : []
  for (const step of body.steps) {
    if (step.next === id) slots.push({ kind: 'next', step: step.id })
    step.branches?.forEach((b, index) => {
      if (b.next === id) slots.push({ kind: 'branch', step: step.id, index })
    })
    if (step.otherwise === id) slots.push({ kind: 'otherwise', step: step.id })
  }
  return slots
}

/**
 * The slot a new step goes into "after" [after]: the start, a step's `next`, or a branch's `otherwise` (the new step then
 * takes over where it led).
 */
function slotAfter(body: DefinitionBody, after: string): Slot | undefined {
  if (after === START_NODE) return { kind: 'start' }
  const step = body.steps.find((s) => s.id === after)
  if (!step) return undefined
  return step.type === 'branch' ? { kind: 'otherwise', step: after } : { kind: 'next', step: after }
}

function add(body: DefinitionBody, after: string, step: StepJson): DefinitionBody {
  const slot = slotAfter(body, after)
  if (!slot || body.steps.some((s) => s.id === step.id)) return body
  const previous = targetOf(body, slot) ?? END
  const added: StepJson = step.type === 'branch' ? { ...step, otherwise: previous } : { ...step, next: previous }
  const index = after === START_NODE ? 0 : body.steps.findIndex((s) => s.id === after) + 1
  const steps = [...body.steps.slice(0, index), added, ...body.steps.slice(index)]
  return connect(withSteps(body, steps), slot, step.id)
}

/**
 * Removes a step. What led to it is re-pointed to where it led when that is one place (a step with a `next`); otherwise
 * those connections are left pointing at the removed id, and validation says so (design E06 §3).
 */
function remove(body: DefinitionBody, id: string): DefinitionBody {
  const step = body.steps.find((s) => s.id === id)
  if (!step) return body
  const onward = step.type === 'branch' ? undefined : step.next
  const without = withSteps(
    body,
    body.steps.filter((s) => s.id !== id),
  )
  if (!onward) return without
  return incoming(without, id).reduce((b, slot) => connect(b, slot, onward), without)
}

/** The body after [command]; a command that names a step that does not exist changes nothing. */
export function apply(body: DefinitionBody, command: Command): DefinitionBody {
  switch (command.kind) {
    case 'add':
      return add(body, command.after, command.step)
    case 'connect':
      return connect(body, command.from, command.to)
    case 'remove':
      return remove(body, command.id)
    case 'set':
      return mapStep(body, command.id, (s) => patchStep(s, command.patch))
    case 'addBranch':
      return mapStep(body, command.id, (s) => ({ ...s, branches: [...(s.branches ?? []), command.branch] }))
    case 'removeBranch':
      return mapStep(body, command.id, (s) => ({
        ...s,
        branches: (s.branches ?? []).filter((_, i) => i !== command.index),
      }))
  }
}

/** A step id not used yet, from the step's type: `approve`, then `approve-2`, `approve-3`… (`^[a-z][a-z0-9-]{0,39}$`). */
export function newStepId(body: DefinitionBody, type: StepType): string {
  const base = type === 'escalate-to-forum' ? 'forum' : type
  const used = new Set(body.steps.map((s) => s.id))
  if (!used.has(base)) return base
  let n = 2
  while (used.has(`${base}-${String(n)}`)) n++
  return `${base}-${String(n)}`
}

/**
 * A new step of [type], named [name], with what the type needs to start from. People, conditions and message keys are
 * left for the author; validation lists what is missing.
 */
export function newStep(body: DefinitionBody, type: StepType, name: string): StepJson {
  const id = newStepId(body, type)
  switch (type) {
    case 'review':
      return { id, type, name, actions: ['RECOMMEND', 'RETURN'] }
    case 'approve':
      return { id, type, name, actions: ['APPROVE', 'RETURN', 'REJECT'] }
    case 'parallel':
      return { id, type, name, mode: 'all', assignees: [] }
    case 'branch':
      return { id, type, name, branches: [{ when: '', next: END }] }
    case 'notify':
      return { id, type, name, recipients: [{ kind: 'initiator' }], message: '' }
    case 'subworkflow':
      return { id, type, name, definition: '' }
    case 'escalate-to-forum':
      return { id, type, name, forum: '' }
    default:
      return { id, type, name }
  }
}

/** The step types the palette offers; `sign` is refused until Phase 5 (design E06 §5). */
export const PALETTE: StepType[] = [
  'review',
  'approve',
  'parallel',
  'doa',
  'route',
  'branch',
  'notify',
  'subworkflow',
  'escalate-to-forum',
]

/** Bodies before and after the present one (design E06 §3): about a hundred changes back. */
export interface History {
  past: DefinitionBody[]
  present: DefinitionBody
  future: DefinitionBody[]
}

export const HISTORY_LIMIT = 100

export function history(present: DefinitionBody): History {
  return { past: [], present, future: [] }
}

/** [next] as the present; the old present goes to the past and the redo list is cleared. Unchanged bodies are not recorded. */
export function record(h: History, next: DefinitionBody): History {
  if (next === h.present) return h
  return { past: [...h.past, h.present].slice(-HISTORY_LIMIT), present: next, future: [] }
}

export function undo(h: History): History {
  const previous = h.past.at(-1)
  if (!previous) return h
  return { past: h.past.slice(0, -1), present: previous, future: [h.present, ...h.future] }
}

export function redo(h: History): History {
  const next = h.future[0]
  if (!next) return h
  return { past: [...h.past, h.present], present: next, future: h.future.slice(1) }
}
