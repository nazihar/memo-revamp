import { Graph as DagreGraph, layout as dagreLayout } from '@dagrejs/dagre'
import type { Graph } from './model'

export const NODE_WIDTH = 260
export const NODE_HEIGHT = 76
export const MARKER_SIZE = 44

export interface Position {
  x: number
  y: number
}

/**
 * Top-down positions for every node (design E06 §2, ADR-0042): computed with dagre after each structural change and
 * never stored. Positions are the top-left corners React Flow expects; dangling edges are left out.
 */
export function layout(graph: Graph): Map<string, Position> {
  const g = new DagreGraph({ multigraph: true })
  g.setGraph({ rankdir: 'TB', nodesep: 48, ranksep: 64, marginx: 16, marginy: 16 })
  g.setDefaultEdgeLabel(() => ({}))
  for (const node of graph.nodes) {
    const marker = node.kind !== 'step'
    g.setNode(node.id, { width: marker ? MARKER_SIZE * 2 : NODE_WIDTH, height: marker ? MARKER_SIZE : NODE_HEIGHT })
  }
  for (const edge of graph.edges) {
    if (!edge.dangling) g.setEdge(edge.source, edge.target, {}, edge.id)
  }
  dagreLayout(g)
  const positions = new Map<string, Position>()
  for (const id of g.nodes()) {
    const n = g.node(id) as { x: number; y: number; width: number; height: number }
    positions.set(id, { x: n.x - n.width / 2, y: n.y - n.height / 2 })
  }
  return positions
}
