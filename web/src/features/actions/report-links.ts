import type { IntlShape } from 'react-intl'
import { al } from './messages'

const COUNTED = [
  ['TO_CLOSE', 'to close', al.countTO_CLOSE],
  ['OVERDUE', 'overdue', al.countOVERDUE],
  ['OPEN', 'open', al.countOPEN],
  ['CLOSED', 'closed since the last meeting', al.countCLOSED],
  ['CANCELLED', 'cancelled since the last meeting', al.countCANCELLED],
  ['CORRECTIONS', 'corrections to earlier minutes', al.countCORRECTIONS],
] as const

/**
 * The reader's counts from the report link's label. The server writes it in English for every reader ("Action taken
 * report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting", ReportWords.summary, the counts the reader's
 * own); the agenda shows the counts in the reader's language, so they are read back from those fixed words. Undefined
 * when the label is not in that form (the label itself is shown then).
 */
export function reportCounts(label: string): Partial<Record<(typeof COUNTED)[number][0], number>> | undefined {
  const prefix = 'Action taken report: '
  if (!label.startsWith(prefix)) return undefined
  const rest = label.slice(prefix.length)
  if (rest === 'nothing to report') return {}
  const counts: Partial<Record<(typeof COUNTED)[number][0], number>> = {}
  for (const part of rest.split(', ')) {
    const match = /^(\d+) (.+)$/.exec(part)
    const known = match ? COUNTED.find(([, words]) => words === match[2]) : undefined
    if (!match || !known) return undefined
    counts[known[0]] = Number(match[1])
  }
  return counts
}

export function countsLine(intl: IntlShape, label: string): string {
  const counts = reportCounts(label)
  if (!counts) return label
  const parts = COUNTED.flatMap(([key, , message]) =>
    counts[key] ? [intl.formatMessage(message, { count: counts[key] })] : [],
  )
  return parts.length ? parts.join(' · ') : intl.formatMessage(al.reportNothing)
}
