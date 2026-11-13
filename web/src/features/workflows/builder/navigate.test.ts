import { describe, expect, it } from 'vitest'
import type { DefinitionBody } from './definition'
import { END_NODE, START_NODE, toGraph } from './model'
import { neighbour } from './navigate'

const body: DefinitionBody = {
  schemaVersion: 2,
  start: 'size',
  steps: [
    { id: 'size', type: 'branch', branches: [{ when: 'item.a > 1', next: 'board' }], otherwise: 'buyer' },
    { id: 'board', type: 'approve', next: 'end' },
    { id: 'buyer', type: 'approve', next: 'end' },
  ],
}

describe('moving along connections with the arrow keys', () => {
  const graph = toGraph(body)
  it('goes down and up the connections, and across between the steps one step leads to', () => {
    expect(neighbour(graph, START_NODE, 'down')).toBe('size')
    expect(neighbour(graph, 'size', 'down')).toBe('board')
    expect(neighbour(graph, 'board', 'next')).toBe('buyer')
    expect(neighbour(graph, 'buyer', 'previous')).toBe('board')
    expect(neighbour(graph, 'buyer', 'next')).toBeUndefined()
    expect(neighbour(graph, 'buyer', 'down')).toBe(END_NODE)
    expect(neighbour(graph, 'size', 'up')).toBe(START_NODE)
    expect(neighbour(graph, START_NODE, 'up')).toBeUndefined()
  })
})
