import { Link } from '@tanstack/react-router'
import { ClipboardList, ListChecks } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { ItemLink, MemoOriginLabel } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { al } from './messages'
import { countsLine } from './report-links'

/**
 * The "Matters arising" item's link to its action taken report on the agenda (flow AO, through the item-links port):
 * "Action taken report (as published on 28 November)", the reader's counts ("2 proposed to close · 3 overdue · 9 open ·
 * 4 closed since the last meeting") and "Open the report". Only readers the report is for get the link.
 */
export function ActionReportLinks({ links }: { links: ItemLink[] | undefined }) {
  const intl = useIntl()
  const reports = (links ?? []).filter((l) => l.kind === 'ACTION_REPORT' && l.meetingId)
  if (!reports.length) return null
  return (
    <ul className="flex flex-col gap-2">
      {reports.map((l) => (
        <li key={`${l.meetingId ?? ''}-report`} className="flex flex-col gap-2 rounded-md border p-3">
          <p className="flex items-center gap-2 font-medium">
            <ClipboardList aria-hidden="true" className="size-5 shrink-0" />
            {intl.formatMessage(al.reportLink, {
              date: l.at ? intl.formatDate(l.at, { day: 'numeric', month: 'long' }) : '',
            })}
          </p>
          <p>{countsLine(intl, l.label ?? '')}</p>
          <div className="print:hidden">
            <Button asChild variant="outline" className="min-h-11 text-base">
              <Link to="/meetings/$meetingId/action-report" params={{ meetingId: l.meetingId ?? '' }}>
                {intl.formatMessage(al.reportOpen)}
              </Link>
            </Button>
          </div>
        </li>
      ))}
    </ul>
  )
}

/**
 * "Raised from action BRC A9/2026 (Board Risk Committee)" at the top of a memo (flow AP, design §1.11 T6): the reference
 * links to the action only for readers who see it (the server gives its id to them alone); others read the words.
 */
export function MemoOrigins({ labels }: { labels: MemoOriginLabel[] | undefined }) {
  const intl = useIntl()
  const terms = useTerms()
  const actions = (labels ?? []).filter((o) => o.recordType === 'ACTION')
  if (!actions.length) return null
  return (
    <ul aria-label={intl.formatMessage(al.origins, terms)} className="mb-4 flex flex-col gap-1">
      {actions.map((o) => {
        const words = intl.formatMessage(o.kind === 'RAISED_FROM' ? al.raisedFrom : al.linkedTo, {
          reference: o.reference,
          committee: o.owner,
        })
        return (
          <li key={`${o.kind}-${o.reference}`} className="flex items-center gap-2 text-lg">
            <ListChecks aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
            {o.recordId ? (
              <Link
                to="/actions/$actionId"
                params={{ actionId: o.recordId }}
                className="inline-flex min-h-11 items-center underline underline-offset-4"
              >
                {words}
              </Link>
            ) : (
              <span>{words}</span>
            )}
          </li>
        )
      })}
    </ul>
  )
}
