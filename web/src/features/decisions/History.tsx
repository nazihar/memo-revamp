import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { getItemDecisionHistoryOptions } from '@/api/generated/@tanstack/react-query.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { dmv } from './messages'

/**
 * The decision's history (flow AA2, "History"): each entry with its outcome, who recorded or corrected it, when and why
 * ("Recorded 10:42 by K. Secretary · Corrected 14 Dec by K. Secretary: Condition wording agreed with the chair").
 */
export function History({ meetingId, itemId, timeZone }: { meetingId: string; itemId: string; timeZone: string }) {
  const intl = useIntl()
  const history = useQuery(getItemDecisionHistoryOptions({ path: { meetingId, itemId } }))
  if (history.isPending) return <LoadingState rows={2} />
  if (history.isError) return <ErrorState onRetry={() => void history.refetch()} />
  const time = (at: string) => intl.formatDate(at, { dateStyle: 'medium', timeStyle: 'short', timeZone })
  return (
    <ol className="flex flex-col gap-2 border-s-2 ps-4 text-base">
      {history.data.entries.map((e) => (
        <li key={e.seq} className="flex flex-col">
          <span className="font-medium">{intl.formatMessage(dmv.historyEntry, { outcome: e.outcomeLabel })}</span>
          <span className="text-muted-foreground">
            {e.correction
              ? intl.formatMessage(dmv.historyCorrected, {
                  time: time(e.recordedAt),
                  name: e.recordedBy.name,
                  reason: e.reason ?? '',
                })
              : intl.formatMessage(dmv.historyRecorded, { time: time(e.recordedAt), name: e.recordedBy.name })}
          </span>
        </li>
      ))}
    </ol>
  )
}
