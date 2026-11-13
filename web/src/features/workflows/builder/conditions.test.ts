import { fc, test } from '@fast-check/vitest'
import { describe, expect, it } from 'vitest'
import { parseCondition, printCondition, type ConditionRow, type SimpleCondition } from './conditions'

describe('the simple form of conditions', () => {
  it('reads one join of simple comparisons', () => {
    expect(parseCondition('item.classificationRank >= 2')).toEqual({
      join: 'all',
      rows: [{ attribute: 'classificationRank', op: '>=', value: 2 }],
    })
    expect(parseCondition('item.amount > 1000.5 && item.category == "loan"')).toEqual({
      join: 'all',
      rows: [
        { attribute: 'amount', op: '>', value: 1000.5 },
        { attribute: 'category', op: '==', value: 'loan' },
      ],
    })
    expect(parseCondition('item.category in ["loan", "lease"] || item.urgent == true')).toEqual({
      join: 'any',
      rows: [
        { attribute: 'category', op: 'in', value: ['loan', 'lease'] },
        { attribute: 'urgent', op: '==', value: true },
      ],
    })
    expect(parseCondition('item.note == "a \\"quoted\\" && b"')).toEqual({
      join: 'all',
      rows: [{ attribute: 'note', op: '==', value: 'a "quoted" && b' }],
    })
  })

  it('leaves everything else as CEL', () => {
    for (const text of [
      '',
      'true',
      'item.a > 1 && item.b < 2 || item.c == 3',
      '(item.a > 1)',
      'item.a + 1 > 2',
      'size(item.tags) > 0',
      'item.a > item.b',
      'item.when > timestamp("2026-01-01T00:00:00Z")',
      'item.a in []',
      'item.a == "unterminated',
    ]) {
      expect(parseCondition(text), text).toBeUndefined()
    }
  })

  it('writes CEL, with doubles always carrying a decimal point', () => {
    const condition: SimpleCondition = {
      join: 'any',
      rows: [
        { attribute: 'amount', op: '>=', value: 1000 },
        { attribute: 'rank', op: '>=', value: 2 },
        { attribute: 'category', op: 'in', value: ['loan', 'le"ase'] },
      ],
    }
    expect(printCondition(condition, { amount: 'double', rank: 'int', category: 'string' })).toBe(
      'item.amount >= 1000.0 || item.rank >= 2 || item.category in ["loan", "le\\"ase"]',
    )
  })
})

const attribute = fc.stringMatching(/^[a-z][a-zA-Z0-9]{0,10}$/)
const row: fc.Arbitrary<ConditionRow> = fc.oneof(
  fc.record({
    attribute,
    op: fc.constantFrom('==' as const, '!=' as const, '<' as const, '<=' as const, '>' as const, '>=' as const),
    value: fc.integer({ min: -1_000_000, max: 1_000_000 }),
  }),
  fc.record({ attribute, op: fc.constantFrom('==' as const, '!=' as const), value: fc.string({ maxLength: 20 }) }),
  fc.record({
    attribute,
    op: fc.constant('in' as const),
    value: fc.array(fc.string({ maxLength: 10 }), { minLength: 1, maxLength: 4 }),
  }),
  fc.record({ attribute, op: fc.constant('==' as const), value: fc.boolean() }),
)

describe('condition properties', () => {
  test.prop([fc.constantFrom('all' as const, 'any' as const), fc.array(row, { minLength: 1, maxLength: 5 })])(
    'rows written as CEL read back as the same rows',
    (join, rows) => {
      const condition = { join: rows.length === 1 ? 'all' : join, rows }
      expect(parseCondition(printCondition(condition))).toEqual(condition)
    },
  )
})
