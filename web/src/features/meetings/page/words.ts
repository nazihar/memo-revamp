import type { IntlShape } from 'react-intl'
import type { ChangedItemResponse, MemberItemResponse, MemberPaperResponse } from '@/api/generated/types.gen'
import { itemFlags, paperFlags, pm, pmv } from './messages'

type ItemFlag = MemberItemResponse['changes'][number]
type PaperFlag = MemberPaperResponse['changes'][number]

/**
 * An item's line as the reader may see it (design §1.9): "7 Quarterly compliance report", "6 Restricted item" when the
 * title is hidden from them, "Break" for a break.
 */
export function lineName(
  intl: IntlShape,
  item: Pick<MemberItemResponse, 'number' | 'title' | 'access'> & { kind?: MemberItemResponse['kind'] },
): string {
  const title =
    item.access === 'HIDDEN' || !item.title
      ? item.kind === 'BREAK'
        ? intl.formatMessage(pm.break)
        : intl.formatMessage(pm.restrictedItem)
      : item.title
  return item.number ? intl.formatMessage(pmv.numbered, { number: item.number, title }) : title
}

/** What a revision changed on an item, in words: "paper replaced, moved (was item 5)"; late and tabled are said apart. */
export function itemChanges(
  intl: IntlShape,
  flags: readonly ItemFlag[],
  previousNumber: string | null | undefined,
  options: { withLate?: boolean } = {},
): string {
  const words = flags
    .filter((f) => options.withLate === true || (f !== 'LATE' && f !== 'TABLED'))
    .map((f) =>
      f === 'MOVED' && previousNumber
        ? intl.formatMessage(pmv.moved, { number: previousNumber })
        : intl.formatMessage(itemFlags[f]),
    )
  return words.length ? intl.formatList(words, { type: 'conjunction', style: 'narrow' }) : ''
}

/** What a revision changed on a paper, in words. */
export function paperChanges(intl: IntlShape, flags: readonly PaperFlag[]): string {
  return flags.length
    ? intl.formatList(
        flags.map((f) => intl.formatMessage(paperFlags[f])),
        { type: 'conjunction', style: 'narrow' },
      )
    : ''
}

/** One line of "What changed": "Item 5 Term loan, Delta Textiles: paper replaced". */
export function changedLine(intl: IntlShape, item: ChangedItemResponse): string {
  return intl.formatMessage(pmv.changedLine, {
    line: lineName(intl, item),
    changes: itemChanges(intl, item.flags, item.previousNumber, { withLate: true }),
  })
}

/** Where a paper's PDF is read: the version the revision pinned, through the meeting (ADR-0061; members read PDFs only). */
export function paperUrl(meetingId: string, paperId: string, revision: number): string {
  const query = new URLSearchParams({ revision: String(revision), kind: 'PDF' })
  return `/api/v1/meetings/${encodeURIComponent(meetingId)}/papers/${encodeURIComponent(paperId)}/content?${query.toString()}`
}

/** The paper's button text: its title and, when known, its pages. */
export function paperLabel(intl: IntlShape, paper: Pick<MemberPaperResponse, 'title' | 'pages'>): string {
  return paper.pages ? intl.formatMessage(pmv.paper, { title: paper.title, pages: paper.pages }) : paper.title
}
