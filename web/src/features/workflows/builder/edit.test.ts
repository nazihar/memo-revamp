import { fc, test } from '@fast-check/vitest'
import { describe, expect, it } from 'vitest'
import type { DefinitionBody, StepType } from './definition'
import {
  apply,
  history,
  HISTORY_LIMIT,
  incoming,
  newStep,
  newStepId,
  record,
  redo,
  undo,
  type Command,
  type History,
} from './edit'
import { START_NODE } from './model'

const body: DefinitionBody = {
  schemaVersion: 2,
  start: 'review',
  steps: [
    { id: 'review', type: 'review', name: 'Credit review', next: 'size' },
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [{ when: 'item.classificationRank >= 2', next: 'committee' }],
      otherwise: 'director',
    },
    { id: 'committee', type: 'parallel', name: 'Committee', next: 'tell' },
    { id: 'director', type: 'approve', name: 'Director', next: 'tell' },
    { id: 'tell', type: 'notify', name: 'Tell', next: 'end' },
  ],
  extra: { kept: true },
}

describe('editing a definition', () => {
  it('adds a step after another: it takes over where that step led', () => {
    const step = newStep(body, 'approve', 'Head of credit')
    const next = apply(body, { kind: 'add', after: 'review', step })
    expect(next.steps.map((s) => s.id)).toEqual(['review', 'approve', 'size', 'committee', 'director', 'tell'])
    expect(next.steps[0]?.next).toBe('approve')
    expect(next.steps[1]).toMatchObject({ id: 'approve', name: 'Head of credit', next: 'size' })
    expect(next.extra).toEqual({ kept: true })
  })

  it('adds a step at the start, and after a branch on its otherwise', () => {
    const first = apply(body, { kind: 'add', after: START_NODE, step: newStep(body, 'review', 'Intake') })
    expect(first.start).toBe('review-2')
    expect(first.steps[0]).toMatchObject({ id: 'review-2', next: 'review' })

    const afterBranch = apply(body, { kind: 'add', after: 'size', step: newStep(body, 'notify', 'Tell the desk') })
    expect(afterBranch.steps.find((s) => s.id === 'size')?.otherwise).toBe('notify')
    expect(afterBranch.steps.find((s) => s.id === 'notify')?.next).toBe('director')
  })

  it('removes a step and re-points what led to it to where it led', () => {
    const next = apply(body, { kind: 'remove', id: 'committee' })
    expect(next.steps.map((s) => s.id)).not.toContain('committee')
    expect(next.steps.find((s) => s.id === 'size')?.branches?.[0]?.next).toBe('tell')
    // A branch has no single way on: what led to it is left for validation to report.
    const noBranch = apply(body, { kind: 'remove', id: 'size' })
    expect(noBranch.steps.find((s) => s.id === 'review')?.next).toBe('size')
    expect(incoming(noBranch, 'size')).toEqual([{ kind: 'next', step: 'review' }])
  })

  it('connects slots, renames, sets and clears properties, and edits branches', () => {
    let next = apply(body, { kind: 'connect', from: { kind: 'otherwise', step: 'size' }, to: 'tell' })
    expect(next.steps.find((s) => s.id === 'size')?.otherwise).toBe('tell')
    next = apply(next, { kind: 'connect', from: { kind: 'branch', step: 'size', index: 0 }, to: 'director' })
    expect(next.steps.find((s) => s.id === 'size')?.branches?.[0]?.next).toBe('director')
    next = apply(next, { kind: 'set', id: 'director', patch: { name: 'Managing director', sla: { due: 'P2D' } } })
    expect(next.steps.find((s) => s.id === 'director')).toMatchObject({
      name: 'Managing director',
      sla: { due: 'P2D' },
    })
    next = apply(next, { kind: 'set', id: 'director', patch: { sla: undefined } })
    expect('sla' in (next.steps.find((s) => s.id === 'director') ?? {})).toBe(false)
    next = apply(next, { kind: 'addBranch', id: 'size', branch: { when: 'item.classificationRank == 1', next: 'end' } })
    expect(next.steps.find((s) => s.id === 'size')?.branches).toHaveLength(2)
    next = apply(next, { kind: 'removeBranch', id: 'size', index: 0 })
    expect(next.steps.find((s) => s.id === 'size')?.branches?.[0]?.when).toBe('item.classificationRank == 1')
  })

  it('sets a condition and declares the attributes it reads, once', () => {
    const declare = [{ name: 'amount', type: 'double' }]
    let next = apply(body, { kind: 'setCondition', id: 'size', index: 0, when: 'item.amount > 1000.0', declare })
    expect(next.steps.find((s) => s.id === 'size')?.branches?.[0]?.when).toBe('item.amount > 1000.0')
    expect(next.attributes).toEqual(declare)
    next = apply(next, { kind: 'setCondition', id: 'size', index: 0, when: 'item.amount > 5.0', declare })
    expect(next.attributes).toEqual(declare)
  })

  it('gives new steps unused ids from their type', () => {
    expect(newStepId(body, 'approve')).toBe('approve')
    expect(newStepId(body, 'review')).toBe('review-2')
    expect(newStepId(body, 'escalate-to-forum')).toBe('forum')
  })

  it('undoes and redoes, and keeps at most a hundred changes', () => {
    let h: History = history(body)
    h = record(h, apply(h.present, { kind: 'remove', id: 'tell' }))
    h = record(h, apply(h.present, { kind: 'set', id: 'review', patch: { name: 'Intake' } }))
    expect(undo(undo(h)).present).toBe(body)
    expect(redo(undo(h)).present).toBe(h.present)
    expect(record(h, h.present)).toBe(h)
    for (let i = 0; i < HISTORY_LIMIT + 10; i++) h = record(h, { ...h.present, schemaVersion: i })
    expect(h.past).toHaveLength(HISTORY_LIMIT)
  })
})

const types: StepType[] = ['review', 'approve', 'parallel', 'branch', 'notify', 'route', 'doa']

/** A command against whatever body it meets: ids are picked by position, so most commands hit a real step. */
const commands = fc.array(
  fc.oneof(
    fc.tuple(fc.nat(), fc.constantFrom(...types)).map(([at, type]) => ({ op: 'add' as const, at, type })),
    fc.nat().map((at) => ({ op: 'remove' as const, at })),
    fc.tuple(fc.nat(), fc.nat()).map(([at, to]) => ({ op: 'connect' as const, at, to })),
    fc.tuple(fc.nat(), fc.string({ maxLength: 20 })).map(([at, name]) => ({ op: 'rename' as const, at, name })),
  ),
  { maxLength: 25 },
)

type Op =
  | { op: 'add'; at: number; type: StepType }
  | { op: 'remove'; at: number }
  | { op: 'connect'; at: number; to: number }
  | { op: 'rename'; at: number; name: string }

function commandFor(current: DefinitionBody, op: Op): Command {
  const ids = [START_NODE, ...current.steps.map((s) => s.id)]
  const id = (n: number) => ids[n % ids.length] ?? START_NODE
  const stepId = (n: number) => current.steps[n % Math.max(current.steps.length, 1)]?.id ?? 'none'
  switch (op.op) {
    case 'add':
      return { kind: 'add', after: id(op.at), step: newStep(current, op.type, op.type) }
    case 'remove':
      return { kind: 'remove', id: stepId(op.at) }
    case 'connect':
      return { kind: 'connect', from: { kind: 'next', step: stepId(op.at) }, to: id(op.to + 1) }
    case 'rename':
      return { kind: 'set', id: stepId(op.at), patch: { name: op.name } }
  }
}

describe('editing properties', () => {
  test.prop([commands])('any edits, all undone, give back the body they started from', (ops) => {
    let h = history(body)
    for (const op of ops) h = record(h, apply(h.present, commandFor(h.present, op)))
    const back = ops.reduce((x) => undo(x), h)
    expect(back.present).toEqual(body)
  })

  test.prop([commands])('step ids stay unique, and nothing outside the steps changes', (ops) => {
    let current = body
    for (const op of ops) current = apply(current, commandFor(current, op))
    const ids = current.steps.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
    expect(current.extra).toEqual(body.extra)
    expect(current.schemaVersion).toBe(2)
  })
})
