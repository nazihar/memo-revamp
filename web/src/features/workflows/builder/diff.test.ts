import { fc, test } from '@fast-check/vitest'
import { describe, expect, it } from 'vitest'
import type { DefinitionBody, StepJson } from './definition'
import { diff, isEmpty, same } from './diff'

const v1: DefinitionBody = {
  schemaVersion: 2,
  start: 'review',
  steps: [
    { id: 'review', type: 'review', name: 'Credit review', next: 'size' },
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [{ when: 'item.classificationRank >= 2', next: 'director' }],
      otherwise: 'director',
    },
    { id: 'director', type: 'approve', name: 'Director', next: 'tell' },
    { id: 'tell', type: 'notify', name: 'Tell the author', next: 'end' },
  ],
}

const v2: DefinitionBody = {
  ...v1,
  resubmit: 'resume',
  steps: [
    { id: 'review', type: 'review', name: 'Credit review', next: 'size' },
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [
        { when: 'item.classificationRank >= 3', next: 'director' },
        { when: 'item.classificationRank == 1', next: 'end' },
      ],
      otherwise: 'director',
    },
    { id: 'director', type: 'approve', name: 'Director', sla: { due: 'P2D', remind: { before: 'PT4H' } }, next: 'end' },
    { id: 'board', type: 'approve', name: 'Board', next: 'end' },
  ],
}

describe('the diff between two versions', () => {
  it('lists added, removed and changed steps with the properties that differ', () => {
    const d = diff(v1, v2)
    expect(d.top).toEqual([{ path: 'resubmit', before: undefined, after: 'resume' }])
    expect(d.steps.map((s) => `${s.id}:${s.kind}`)).toEqual([
      'size:changed',
      'director:changed',
      'board:added',
      'tell:removed',
    ])
    expect(d.steps[0]?.changes).toEqual([
      { path: 'branches[0].when', before: 'item.classificationRank >= 2', after: 'item.classificationRank >= 3' },
      { path: 'branches[1]', before: undefined, after: { when: 'item.classificationRank == 1', next: 'end' } },
    ])
    expect(d.steps[1]?.changes).toEqual([
      { path: 'next', before: 'tell', after: 'end' },
      { path: 'sla', before: undefined, after: { due: 'P2D', remind: { before: 'PT4H' } } },
    ])
  })

  it('names the part of a deadline that moved, and ignores key order', () => {
    const before: StepJson = { id: 'a', type: 'approve', sla: { due: 'P2D', remind: { before: 'PT4H' } } }
    const after: StepJson = { type: 'approve', id: 'a', sla: { remind: { before: 'PT2H' }, due: 'P2D' } }
    const d = diff({ ...v1, steps: [before] }, { ...v1, steps: [after] })
    expect(d.steps[0]?.changes).toEqual([{ path: 'sla.remind.before', before: 'PT4H', after: 'PT2H' }])
    expect(same({ a: 1, b: [1, { c: 2 }] }, { b: [1, { c: 2 }], a: 1 })).toBe(true)
  })
})

const json: fc.Arbitrary<unknown> = fc.letrec((tie) => ({
  value: fc.oneof(
    { depthSize: 'small' },
    fc.string({ maxLength: 5 }),
    fc.integer(),
    fc.boolean(),
    fc.array(tie('value'), { maxLength: 3 }),
    fc.dictionary(fc.constantFrom('a', 'b', 'c'), tie('value'), { maxKeys: 3 }),
  ),
})).value

const bodies: fc.Arbitrary<DefinitionBody> = fc
  .uniqueArray(fc.constantFrom('a', 'b', 'c', 'd', 'e'), { maxLength: 5 })
  .chain((ids) =>
    fc
      .tuple(
        ...ids.map((id) =>
          fc
            .record({ name: fc.string({ maxLength: 5 }), next: fc.constantFrom('end', ...ids), extra: json })
            .map((r): StepJson => ({ id, type: 'approve', ...r })),
        ),
      )
      .map((steps) => ({ schemaVersion: 2, start: ids[0] ?? 'end', steps })),
  )

describe('diff properties', () => {
  test.prop([bodies])('a version compared with itself has no changes', (body) => {
    expect(isEmpty(diff(body, structuredClone(body)))).toBe(true)
  })

  test.prop([bodies, bodies])('every step id appears in the diff at most once, and only if it differs', (a, b) => {
    const d = diff(a, b)
    const ids = d.steps.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const s of d.steps) {
      const before = a.steps.find((x) => x.id === s.id)
      const after = b.steps.find((x) => x.id === s.id)
      expect(s.kind).toBe(before ? (after ? 'changed' : 'removed') : 'added')
      if (before && after) expect(same(before, after)).toBe(false)
    }
  })
})
