import { Link } from '@tanstack/react-router'
import { LockKeyhole } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { DecisionRowView } from '@/api/generated/types.gen'
import { drm } from './messages'
import {
  dayWords,
  exceptionWords,
  meetingWords,
  numberStateWords,
  outcomeWords,
  rowFocusId,
  rowStateWords,
} from './words'

const LINK =
  'inline-flex min-h-11 items-center text-primary underline underline-offset-4 hover:decoration-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring'

/**
 * One decision of the register (flow AS1). In full: the date, the meeting and item, the resolution number and whether it
 * is in force, the title (opening the meeting where it was decided), the outcome, the exceptions and the state in words,
 * and the memo. A restricted line shows the number, the meeting and the item, and why it is restricted, in words: never
 * a title, an outcome or a memo (design §1.3).
 */
export function RegisterRow({
  row,
  today,
  onOpen,
}: {
  row: DecisionRowView
  today: string
  onOpen: (row: DecisionRowView) => void
}) {
  const intl = useIntl()
  const titleId = `${rowFocusId(row)}-title`
  const number = row.resolutionNumber
    ? intl.formatMessage(drm.resolution, { number: row.resolutionNumber })
    : intl.formatMessage(drm.noNumber)
  const numberState = numberStateWords(intl, row)
  const where = [dayWords(intl, row.meetingDate), meetingWords(intl, row), row.forumName].join(' · ')

  if (row.level === 'LINE') {
    return (
      <article
        id={rowFocusId(row)}
        tabIndex={-1}
        aria-labelledby={titleId}
        className="flex flex-col gap-1 px-4 py-3 focus-visible:outline-2 focus-visible:outline-ring"
      >
        <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h3 id={titleId} className="flex items-center gap-2 font-medium">
            <LockKeyhole aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            {intl.formatMessage(drm.restricted)}
          </h3>
          <p className="text-sm font-medium">
            {number}
            {numberState ? <span className="ms-2 font-normal text-muted-foreground">{numberState}</span> : null}
          </p>
        </div>
        <p className="text-sm">
          {intl.formatMessage(row.reason === 'RECUSED' ? drm.reasonRecused : drm.reasonRestricted)}
        </p>
        <p className="text-xs text-muted-foreground">{[where, rowStateWords(intl, row)].join(' · ')}</p>
      </article>
    )
  }

  const title =
    row.title ??
    (row.itemNumber
      ? intl.formatMessage(drm.untitled, { item: row.itemNumber })
      : intl.formatMessage(drm.untitledNoItem))
  const outcome = row.outcomeLabel ?? (row.outcomeKind ? outcomeWords(intl, row.outcomeKind) : undefined)
  const exceptions = exceptionWords(intl, row, today)
  return (
    <article aria-labelledby={titleId} className="flex flex-col gap-1 px-4 py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h3 id={titleId} className="min-w-0 flex-[1_1_18rem] font-medium">
          <Link
            id={rowFocusId(row)}
            to="/meetings/$meetingId"
            params={{ meetingId: row.meetingId }}
            className={LINK}
            onClick={() => {
              onOpen(row)
            }}
          >
            {title}
          </Link>
        </h3>
        <p className="text-sm font-medium">
          {number}
          {numberState ? <span className="ms-2 font-normal text-muted-foreground">{numberState}</span> : null}
        </p>
      </div>
      {outcome ? <p className="text-sm font-semibold uppercase tracking-wide">{outcome}</p> : null}
      {exceptions.length > 0 ? <p className="text-sm">{exceptions.join(' · ')}</p> : null}
      <p className="text-xs text-muted-foreground">{[where, rowStateWords(intl, row)].join(' · ')}</p>
      {row.memoId ? (
        <p className="text-sm">
          <Link
            to="/memos/$memoId"
            params={{ memoId: row.memoId }}
            className={LINK}
            onClick={() => {
              onOpen(row)
            }}
          >
            {row.memoNumber
              ? intl.formatMessage(drm.memoLink, { number: row.memoNumber })
              : intl.formatMessage(drm.memoLinkNoNumber)}
          </Link>
        </p>
      ) : null}
    </article>
  )
}
