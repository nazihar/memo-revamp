import { describe, expect, it } from 'vitest'
import { inLegacyOrder, moved, orderBreak } from './mapping-order'

const ranks: Record<string, number> = { internal: 0, confidential: 1, secret: 2 }
const rankOf = (target: string) => ranks[target]

describe('classification order (design E06 §6.3)', () => {
  it('lists markings in the admin’s order, those without an order last', () => {
    const rows = [
      { legacyValue: 'Secret', legacyOrder: 3 },
      { legacyValue: 'Zeta', legacyOrder: null },
      { legacyValue: 'Open', legacyOrder: 1 },
      { legacyValue: 'Alpha', legacyOrder: null },
    ]
    expect(inLegacyOrder(rows).map((r) => r.legacyValue)).toEqual(['Open', 'Secret', 'Alpha', 'Zeta'])
  })

  it('moves a row up or down one place, and not past either end', () => {
    expect(moved(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
    expect(moved(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(moved(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c'])
    expect(moved(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c'])
  })

  it('accepts an order that never maps a stricter marking lower', () => {
    expect(
      orderBreak(
        [
          { legacyValue: 'Open', target: 'internal' },
          { legacyValue: 'Restricted', target: 'internal' },
          { legacyValue: 'Unmapped', target: null },
          { legacyValue: 'Strictly private', target: 'secret' },
        ],
        rankOf,
      ),
    ).toBeUndefined()
  })

  it('names the pair when a stricter marking maps to a lower level', () => {
    expect(
      orderBreak(
        [
          { legacyValue: 'Open', target: 'internal' },
          { legacyValue: 'Restricted', target: 'secret' },
          { legacyValue: 'Strictly private', target: 'confidential' },
        ],
        rankOf,
      ),
    ).toEqual({ lessStrict: 'Restricted', stricter: 'Strictly private' })
  })

  it('finds the break again after a move puts the rows right', () => {
    const rows = [
      { legacyValue: 'Secret', target: 'secret' },
      { legacyValue: 'Open', target: 'internal' },
    ]
    expect(orderBreak(rows, rankOf)).toEqual({ lessStrict: 'Secret', stricter: 'Open' })
    expect(orderBreak(moved(rows, 1, -1), rankOf)).toBeUndefined()
  })
})
