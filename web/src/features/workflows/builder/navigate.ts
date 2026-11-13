import type { Graph } from './model'

export type Direction = 'down' | 'up' | 'previous' | 'next'

/**
 * Where the arrow keys take the focus from node [id] (design E06 §7): down to the first step it leads to, up to the
 * first step that leads to it, and across (previous, next) among the steps the same step leads to. Undefined when
 * there is nowhere to go.
 */
export function neighbour(graph: Graph, id: string, direction: Direction): string | undefined {
  const edges = graph.edges.filter((e) => !e.dangling)
  if (direction === 'down') return edges.find((e) => e.source === id)?.target
  if (direction === 'up') return edges.find((e) => e.target === id)?.source
  const parent = edges.find((e) => e.target === id)?.source
  if (parent === undefined) return undefined
  const siblings = [...new Set(edges.filter((e) => e.source === parent).map((e) => e.target))]
  const at = siblings.indexOf(id)
  return siblings[direction === 'next' ? at + 1 : at - 1]
}
