import { ArrowRight } from 'lucide-react'
import { useIntl } from 'react-intl'
import { Badge } from '@/components/ui/badge'
import type { Change } from './changes'
import { ft, ftv } from './messages'

/**
 * The Changes tab (flow J7), opened first for the reviewer: one "before → after" line per change against the
 * published version.
 */
export function ChangesTab({ changes, first }: { changes: Change[]; first: boolean }) {
  const intl = useIntl()
  if (first) return <p className="text-sm">{intl.formatMessage(ft.changesFirst)}</p>
  if (changes.length === 0) return <p className="text-sm">{intl.formatMessage(ft.changesNone)}</p>
  return (
    <div className="flex max-w-4xl flex-col gap-2">
      <p className="text-sm text-muted-foreground">{intl.formatMessage(ft.changesLead)}</p>
      <ul aria-label={intl.formatMessage(ft.changesList)} className="flex flex-col divide-y rounded-md border">
        {changes.map((c) => (
          <li
            key={c.id}
            className="flex flex-col gap-1 p-3 text-sm sm:flex-row sm:flex-wrap sm:items-baseline sm:gap-2"
          >
            <span className="font-medium">{intl.formatMessage(ftv.changeWhat, { what: c.what })}</span>
            {c.before != null && c.after != null ? (
              <span className="flex flex-wrap items-baseline gap-2">
                <span className="text-muted-foreground">{c.before}</span>
                <ArrowRight aria-hidden="true" className="size-4 shrink-0 self-center rtl:rotate-180" />
                <span className="sr-only">{intl.formatMessage(ft.changeTo)}</span>
                <span>{c.after}</span>
              </span>
            ) : c.after != null ? (
              <span className="flex flex-wrap items-center gap-2">
                <Badge tone="ok">{intl.formatMessage(ft.added)}</Badge>
                {c.after}
              </span>
            ) : (
              <span className="flex flex-wrap items-center gap-2">
                <Badge tone="danger">{intl.formatMessage(ft.removed)}</Badge>
                {c.before}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
