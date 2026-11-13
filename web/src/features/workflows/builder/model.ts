import { END, type DefinitionBody, type StepJson } from './definition'

/** Node ids of the start and end markers; `#` never appears in a step id (`^[a-z][a-z0-9-]{0,39}$`). */
export const START_NODE = '#start'
export const END_NODE = '#end'

export type GraphNode =
  | { id: typeof START_NODE; kind: 'start' }
  | { id: typeof END_NODE; kind: 'end' }
  | { id: string; kind: 'step'; step: StepJson; index: number }

export type EdgeKind = 'start' | 'next' | 'branch' | 'otherwise'

/**
 * A connection, derived from the body and never stored (design E06 §2): `start`, a step's `next`, a branch's
 * `branches[i].next` and its `otherwise`. [dangling] when its target is no step: validation reports it, and the diagram
 * leaves it out.
 */
export interface GraphEdge {
  id: string
  source: string
  target: string
  kind: EdgeKind
  /** For a branch edge, the condition's position in `branches`. */
  branch?: number
  condition?: string
  dangling: boolean
}

export interface Graph {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

function nodeOf(target: string): string {
  return target === END ? END_NODE : target
}

/** The steps a step leads to, in order, as edges. */
export function edgesOf(step: StepJson, known: ReadonlySet<string>): GraphEdge[] {
  const edge = (target: string, kind: EdgeKind, suffix: string, extra: Partial<GraphEdge> = {}): GraphEdge => ({
    id: `${step.id}:${suffix}`,
    source: step.id,
    target: nodeOf(target),
    kind,
    dangling: target !== END && !known.has(target),
    ...extra,
  })
  if (step.type === 'branch') {
    const branches = (step.branches ?? []).map((b, i) =>
      edge(b.next, 'branch', `branch-${String(i)}`, { branch: i, condition: b.when }),
    )
    return step.otherwise ? [...branches, edge(step.otherwise, 'otherwise', 'otherwise')] : branches
  }
  return step.next ? [edge(step.next, 'next', 'next')] : []
}

/** The body as nodes (Start, every step in body order, End) and the connections between them. */
export function toGraph(body: DefinitionBody): Graph {
  const known = new Set(body.steps.map((s) => s.id))
  const nodes: GraphNode[] = [
    { id: START_NODE, kind: 'start' },
    ...body.steps.map((step, index): GraphNode => ({ id: step.id, kind: 'step', step, index })),
    { id: END_NODE, kind: 'end' },
  ]
  const start: GraphEdge = {
    id: `${START_NODE}:start`,
    source: START_NODE,
    target: nodeOf(body.start),
    kind: 'start',
    dangling: body.start !== END && !known.has(body.start),
  }
  return { nodes, edges: [start, ...body.steps.flatMap((s) => edgesOf(s, known))] }
}
