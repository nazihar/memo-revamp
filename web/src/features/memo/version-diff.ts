import { diffLines, diffWords } from 'diff'

/** A run of text on one side, and whether it was removed (left) or added (right). */
export interface DiffSpan {
  text: string
  changed: boolean
}

/** One row of the side-by-side view: what the older and the newer version have there. */
export interface DiffRow {
  left: DiffSpan[] | null
  right: DiffSpan[] | null
  changed: boolean
}

export interface DiffResult {
  rows: DiffRow[]
  /** False when the texts were too different to compare within the budget: the rows are then the texts side by side. */
  compared: boolean
  changes: number
}

/** Work budget of a comparison (research §4): beyond it the texts are shown without change marks. */
const TIMEOUT_MS = 2000
const MAX_EDIT_LENGTH = 20000

const lines = (text: string) => text.split('\n').map((line) => [{ text: line, changed: false }])

/**
 * Lines first, then words inside lines that changed in place (design E02 §3). Pure, so it runs in a worker in the browser
 * and directly in tests.
 */
export function compareTexts(older: string, newer: string): DiffResult {
  const parts = diffLines(older, newer, { timeout: TIMEOUT_MS, maxEditLength: MAX_EDIT_LENGTH })
  if (!parts) {
    const left = lines(older)
    const right = lines(newer)
    return {
      rows: Array.from({ length: Math.max(left.length, right.length) }, (_, i) => ({
        left: left[i] ?? null,
        right: right[i] ?? null,
        changed: false,
      })),
      compared: false,
      changes: 0,
    }
  }
  const rows: DiffRow[] = []
  let changes = 0
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (!part) continue
    const split = part.value.replace(/\n$/, '').split('\n')
    if (!part.added && !part.removed) {
      split.forEach((line) =>
        rows.push({ left: [{ text: line, changed: false }], right: [{ text: line, changed: false }], changed: false }),
      )
      continue
    }
    changes++
    const next = parts[i + 1]
    if (part.removed && next?.added) {
      // Changed in place: pair the lines and mark the words.
      const added = next.value.replace(/\n$/, '').split('\n')
      for (let j = 0; j < Math.max(split.length, added.length); j++) {
        const before = split[j]
        const after = added[j]
        if (before !== undefined && after !== undefined) {
          const words = diffWords(before, after)
          rows.push({
            left: words.filter((w) => !w.added).map((w) => ({ text: w.value, changed: w.removed })),
            right: words.filter((w) => !w.removed).map((w) => ({ text: w.value, changed: w.added })),
            changed: true,
          })
        } else {
          rows.push({
            left: before === undefined ? null : [{ text: before, changed: true }],
            right: after === undefined ? null : [{ text: after, changed: true }],
            changed: true,
          })
        }
      }
      i++
      continue
    }
    split.forEach((line) =>
      rows.push({
        left: part.removed ? [{ text: line, changed: true }] : null,
        right: part.added ? [{ text: line, changed: true }] : null,
        changed: true,
      }),
    )
  }
  return { rows, compared: true, changes }
}
