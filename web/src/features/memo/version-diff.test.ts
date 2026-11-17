import { describe, expect, it } from 'vitest'
import { compareTexts } from './version-diff'

describe('compareTexts (design E02 §3)', () => {
  it('keeps unchanged lines on both sides and marks words inside a changed line', () => {
    const result = compareTexts('Budget for 2026\nApproved by the board', 'Budget for 2027\nApproved by the board')
    expect(result.compared).toBe(true)
    expect(result.changes).toBe(1)
    const changed = result.rows.find((r) => r.changed)
    expect(changed?.left?.filter((s) => s.changed).map((s) => s.text)).toEqual(['2026'])
    expect(changed?.right?.filter((s) => s.changed).map((s) => s.text)).toEqual(['2027'])
    expect(result.rows.filter((r) => !r.changed).map((r) => r.left?.[0]?.text)).toEqual(['Approved by the board'])
  })

  it('shows an added line only on the right and a removed one only on the left', () => {
    const added = compareTexts('One\nTwo', 'One\nTwo\nThree')
    expect(added.rows.at(-1)).toEqual({ left: null, right: [{ text: 'Three', changed: true }], changed: true })
    const removed = compareTexts('One\nTwo\nThree', 'One\nThree')
    expect(removed.rows.find((r) => r.changed)).toEqual({
      left: [{ text: 'Two', changed: true }],
      right: null,
      changed: true,
    })
  })

  it('identical texts have no changes', () => {
    const same = compareTexts('A\nB', 'A\nB')
    expect(same.changes).toBe(0)
    expect(same.rows.every((r) => !r.changed)).toBe(true)
  })
})
