import type { DefinitionBody, StepJson } from './definition'

/** One property that differs; [path] names it within its step (`sla.due`, `branches[1].when`) or the body. */
export interface Change {
  path: string
  before: unknown
  after: unknown
}

export interface StepChange {
  id: string
  kind: 'added' | 'removed' | 'changed'
  /** The step as it is after (or, when removed, before). */
  step: StepJson
  /** For a changed step, what differs; empty for added and removed steps. */
  changes: Change[]
}

export interface DefinitionDiff {
  /** Differences outside the steps: `start`, `attributes`, `resubmit` and anything else. */
  top: Change[]
  /** Steps added, removed or changed, in the order of the newer version, removed steps after. */
  steps: StepChange[]
}

/** Equal as JSON: same keys and values, key order ignored (bodies come from different saves). */
export function same(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((x, i) => same(x, b[i]))
  const ka = Object.keys(a).filter((k) => (a as Record<string, unknown>)[k] !== undefined)
  const kb = Object.keys(b).filter((k) => (b as Record<string, unknown>)[k] !== undefined)
  return (
    ka.length === kb.length &&
    ka.every((k) => same((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k]))
  )
}

/** Keys whose objects are compared one level down, so a change says which part moved (`sla.due`, not `sla`). */
const NESTED = new Set(['sla', 'remind', 'forward'])

function compare(before: unknown, after: unknown, path: string, out: Change[]) {
  if (same(before, after)) return
  const last = path.split('.').at(-1) ?? ''
  const objects =
    typeof before === 'object' &&
    typeof after === 'object' &&
    before &&
    after &&
    !Array.isArray(before) &&
    !Array.isArray(after)
  if (objects && NESTED.has(last)) {
    const b = before as Record<string, unknown>
    const a = after as Record<string, unknown>
    for (const key of [...new Set([...Object.keys(b), ...Object.keys(a)])])
      compare(b[key], a[key], `${path}.${key}`, out)
    return
  }
  if (last === 'branches' && Array.isArray(before) && Array.isArray(after)) {
    const length = Math.max(before.length, after.length)
    for (let i = 0; i < length; i++) {
      const b = before[i] as Record<string, unknown> | undefined
      const a = after[i] as Record<string, unknown> | undefined
      if (!b || !a) out.push({ path: `${path}[${String(i)}]`, before: b, after: a })
      else for (const key of ['when', 'next']) compare(b[key], a[key], `${path}[${String(i)}].${key}`, out)
    }
    return
  }
  out.push({ path, before, after })
}

function stepChanges(before: StepJson, after: StepJson): Change[] {
  const out: Change[] = []
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((k) => k !== 'id')
  for (const key of keys) compare(before[key], after[key], key, out)
  return out
}

/**
 * What changed from [before] to [after] (design E06 §6): steps matched by id, connections compared as the properties
 * they are (`next`, a branch's `next`, `otherwise`). A step whose id changed shows as removed and added, as the engine
 * pins by node id too.
 */
export function diff(before: DefinitionBody, after: DefinitionBody): DefinitionDiff {
  const top: Change[] = []
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((k) => k !== 'steps')
  for (const key of keys) compare(before[key], after[key], key, top)
  const old = new Map(before.steps.map((s) => [s.id, s]))
  const now = new Set(after.steps.map((s) => s.id))
  const steps: StepChange[] = []
  for (const step of after.steps) {
    const previous = old.get(step.id)
    if (!previous) steps.push({ id: step.id, kind: 'added', step, changes: [] })
    else {
      const changes = stepChanges(previous, step)
      if (changes.length > 0) steps.push({ id: step.id, kind: 'changed', step, changes })
    }
  }
  for (const step of before.steps) {
    if (!now.has(step.id)) steps.push({ id: step.id, kind: 'removed', step, changes: [] })
  }
  return { top, steps }
}

/** Whether nothing changed. */
export function isEmpty(d: DefinitionDiff): boolean {
  return d.top.length === 0 && d.steps.length === 0
}
