import type { IntlShape } from 'react-intl'
import type { ItemLink } from '@/api/generated/types.gen'
import { al } from './messages'

const COUNTED = [
  ['TO_CLOSE', al.countTO_CLOSE],
  ['OVERDUE', al.countOVERDUE],
  ['OPEN', al.countOPEN],
  ['CLOSED', al.countCLOSED],
  ['CANCELLED', al.countCANCELLED],
  ['CORRECTIONS', al.countCORRECTIONS],
] as const

/**
 * The reader's counts on the "Matters arising" link, in their language ("2 proposed to close · 3 overdue · 9 open · 4
 * closed since the last meeting"), from the link's structured `counts` (the reader's own, by the report's rule). The
 * English `label` is never read for them; it is shown as it is only for a link that carries no counts.
 */
export function countsLine(intl: IntlShape, link: Pick<ItemLink, 'counts' | 'label'>): string {
  const counts = link.counts
  if (!counts) return link.label ?? ''
  const parts = COUNTED.flatMap(([key, message]) => {
    const count = counts[key] ?? 0
    return count > 0 ? [intl.formatMessage(message, { count })] : []
  })
  return parts.length ? parts.join(' · ') : intl.formatMessage(al.reportNothing)
}
