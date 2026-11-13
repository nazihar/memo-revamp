import { fc, test } from '@fast-check/vitest'
import { describe, expect, it } from 'vitest'
import type { DefinitionBody, StepJson } from './definition'
import { asDefinition } from './definition'
import { layout } from './layout'
import { END_NODE, START_NODE, toGraph } from './model'

/** The demo "Credit facility" (E03 T11) without people: review, a branch, a committee or the director, then a notice. */
const credit: DefinitionBody = {
  schemaVersion: 2,
  attributes: [{ name: 'classificationRank', type: 'int' }],
  start: 'review',
  steps: [
    { id: 'review', type: 'review', name: 'Credit review', assignee: { kind: 'initiator' }, next: 'size' },
    {
      id: 'size',
      type: 'branch',
      name: 'By classification',
      branches: [{ when: 'item.classificationRank >= 2', next: 'committee' }],
      otherwise: 'director',
    },
    { id: 'committee', type: 'parallel', name: 'Credit committee', mode: 'all', next: 'tell' },
    { id: 'director', type: 'approve', name: 'Director', next: 'tell' },
    { id: 'tell', type: 'notify', name: 'Tell the author', next: 'end' },
  ],
}

describe('the graph of a definition', () => {
  it('has Start, every step in body order and End, with one edge per connection', () => {
    const graph = toGraph(credit)
    expect(graph.nodes.map((n) => n.id)).toEqual([
      START_NODE,
      'review',
      'size',
      'committee',
      'director',
      'tell',
      END_NODE,
    ])
    expect(graph.edges.map((e) => `${e.source}>${e.target}:${e.kind}`)).toEqual([
      `${START_NODE}>review:start`,
      'review>size:next',
      'size>committee:branch',
      'size>director:otherwise',
      'committee>tell:next',
      'director>tell:next',
      `tell>${END_NODE}:next`,
    ])
    expect(graph.edges.find((e) => e.kind === 'branch')?.condition).toBe('item.classificationRank >= 2')
    expect(graph.edges.every((e) => !e.dangling)).toBe(true)
  })

  it('marks a connection to a step that does not exist as dangling, and the layout leaves it out', () => {
    const broken: DefinitionBody = { ...credit, steps: [{ id: 'review', type: 'review', next: 'nowhere' }] }
    const graph = toGraph(broken)
    expect(graph.edges.find((e) => e.source === 'review')).toMatchObject({ target: 'nowhere', dangling: true })
    expect(layout(graph).has('nowhere')).toBe(false)
  })

  it('lays the flow out top down: Start above the first step, End below the last', () => {
    const positions = layout(toGraph(credit))
    const y = (id: string) => positions.get(id)?.y ?? Number.NaN
    expect(y(START_NODE)).toBeLessThan(y('review'))
    expect(y('review')).toBeLessThan(y('size'))
    expect(y('size')).toBeLessThan(y('committee'))
    expect(y('tell')).toBeLessThan(y(END_NODE))
  })

  it('lays out a definition of 100 steps, the schema maximum, quickly', () => {
    const steps: StepJson[] = Array.from({ length: 100 }, (_, i) => ({
      id: `s${String(i)}`,
      type: 'approve',
      next: i === 99 ? 'end' : `s${String(i + 1)}`,
    }))
    const started = performance.now()
    layout(toGraph({ schemaVersion: 2, start: 's0', steps }))
    expect(performance.now() - started).toBeLessThan(1000)
  })

  it('reads only bodies with a start and steps that have ids and types', () => {
    expect(asDefinition(credit)).toBe(credit)
    expect(asDefinition({ start: 'a' })).toBeUndefined()
    expect(asDefinition({ start: 'a', steps: [{ id: 'a' }] })).toBeUndefined()
    expect(asDefinition([credit])).toBeUndefined()
  })
})

/** Bodies of up to 20 steps whose connections point at later steps, at End, or at nothing that exists. */
const bodies = fc.integer({ min: 1, max: 20 }).chain((count) => {
  const ids = Array.from({ length: count }, (_, i) => `s${String(i)}`)
  const target = (i: number) => fc.constantFrom(...ids.slice(i + 1), 'end', 'missing')
  const step = (i: number): fc.Arbitrary<StepJson> =>
    fc.oneof(
      target(i).map((next) => ({ id: ids[i] ?? '', type: 'approve' as const, next })),
      fc.tuple(fc.array(target(i), { minLength: 1, maxLength: 3 }), target(i)).map(([nexts, otherwise]) => ({
        id: ids[i] ?? '',
        type: 'branch' as const,
        branches: nexts.map((next, b) => ({ when: `item.a == ${String(b)}`, next })),
        otherwise,
      })),
    )
  return fc
    .tuple(...ids.map((_, i) => step(i)))
    .map((steps): DefinitionBody => ({ schemaVersion: 2, start: 's0', steps }))
})

describe('graph properties', () => {
  test.prop([bodies])('every connection in the body is one edge, and the body is left as it was', (body) => {
    const before = JSON.stringify(body)
    const graph = toGraph(body)
    const connections = body.steps.reduce(
      (n, s) => n + (s.type === 'branch' ? (s.branches?.length ?? 0) + (s.otherwise ? 1 : 0) : s.next ? 1 : 0),
      1,
    )
    expect(graph.edges).toHaveLength(connections)
    expect(graph.nodes).toHaveLength(body.steps.length + 2)
    expect(JSON.stringify(body)).toBe(before)
  })

  test.prop([bodies])('every node gets a position, and dangling edges never reach the layout', (body) => {
    const graph = toGraph(body)
    const positions = layout(graph)
    for (const node of graph.nodes) expect(positions.has(node.id)).toBe(true)
    expect(positions.size).toBe(graph.nodes.length)
  })
})
