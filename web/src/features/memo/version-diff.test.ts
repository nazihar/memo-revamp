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

  it('compares two 50-page texts within the two-second budget (design E02 §9)', () => {
    // About 50 pages of 40 lines; every 25th line edited and a paragraph inserted every 400 lines.
    const older = Array.from(
      { length: 2000 },
      (_, i) => `Line ${String(i)}: the branch budget, the timeline and the risks.`,
    )
    const newer = older.flatMap((line, i) => {
      const edited = i % 25 === 0 ? line.replace('budget', 'revised budget') : line
      return i % 400 === 0 ? [edited, `A new paragraph after line ${String(i)}.`] : [edited]
    })
    const start = performance.now()
    const result = compareTexts(older.join('\n'), newer.join('\n'))
    const elapsed = performance.now() - start
    expect(elapsed).toBeLessThan(2000)
    expect(result.compared).toBe(true)
    expect(result.changes).toBeGreaterThan(0)
  })

  it('identical texts have no changes', () => {
    const same = compareTexts('A\nB', 'A\nB')
    expect(same.changes).toBe(0)
    expect(same.rows.every((r) => !r.changed)).toBe(true)
  })
})
