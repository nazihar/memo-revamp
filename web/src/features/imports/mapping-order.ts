import type { ImportMappingView } from '@/api/generated/types.gen'

/**
 * The classification order rule (design E06 §6.3): the admin orders the legacy markings from least to most strict, and
 * a stricter marking may never map to a lower level than a less strict one. The server refuses such a save
 * (`CLASSIFICATION_ORDER`); the screen checks first, so the error names the pair before a round trip.
 */

/** Mappings in the admin's legacy order (1 = least strict); those without an order last, by value. */
export function inLegacyOrder<T extends Pick<ImportMappingView, 'legacyValue' | 'legacyOrder'>>(
  rows: readonly T[],
): T[] {
  return [...rows].sort((a, b) => {
    const x = a.legacyOrder ?? Number.MAX_SAFE_INTEGER
    const y = b.legacyOrder ?? Number.MAX_SAFE_INTEGER
    return x === y ? a.legacyValue.localeCompare(b.legacyValue) : x - y
  })
}

/** [rows] with the row at [index] moved one place up (-1) or down (+1); unchanged at either end. */
export function moved<T>(rows: readonly T[], index: number, delta: -1 | 1): T[] {
  const to = index + delta
  if (index < 0 || index >= rows.length || to < 0 || to >= rows.length) return [...rows]
  const out = [...rows]
  const [row] = out.splice(index, 1)
  if (row !== undefined) out.splice(to, 0, row)
  return out
}

export interface OrderBreak {
  /** The less strict marking (earlier in the order). */
  lessStrict: string
  /** The stricter marking that maps to a lower level. */
  stricter: string
}

/**
 * The first pair that breaks the rule, for [rows] in legacy order: a later (stricter) marking whose level ranks below
 * an earlier one's. Rows without a target are ignored. [rankOf] gives a target level's rank.
 */
export function orderBreak(
  rows: readonly Pick<ImportMappingView, 'legacyValue' | 'target'>[],
  rankOf: (target: string) => number | undefined,
): OrderBreak | undefined {
  let highest: { value: string; rank: number } | undefined
  for (const row of rows) {
    const rank = row.target ? rankOf(row.target) : undefined
    if (rank === undefined) continue
    if (highest && rank < highest.rank) return { lessStrict: highest.value, stricter: row.legacyValue }
    if (!highest || rank > highest.rank) highest = { value: row.legacyValue, rank }
  }
  return undefined
}
