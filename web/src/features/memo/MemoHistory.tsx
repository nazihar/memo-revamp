import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { getMemoHistoryOptions } from '@/api/generated/@tanstack/react-query.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { cn } from '@/lib/utils'
import { actionLabels, mm, mmv } from './memo-messages'

/**
 * The note sheet (design E01 §1): every action by round, newest round first. Earlier rounds are marked, because their
 * approvals no longer count; each action names the document version it was bound to.
 */
export function MemoHistory({ memoId }: { memoId: string }) {
  const intl = useIntl()
  // The memo page invalidates this query after every action.
  const history = useQuery(getMemoHistoryOptions({ path: { id: memoId } }))
  if (history.isPending) return <LoadingState rows={3} />
  if (history.isError) return <ErrorState onRetry={() => void history.refetch()} />
  if (history.data.length === 0)
    return <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.historyEmpty)}</p>
  return (
    <div className="flex flex-col gap-4">
      {history.data.map((round) => (
        <section key={round.round} aria-label={intl.formatMessage(mmv.round, { round: round.round })}>
          <h3 className="mb-1 text-sm font-semibold">{intl.formatMessage(mmv.round, { round: round.round })}</h3>
          {round.current ? null : (
            <p className="mb-2 text-xs text-muted-foreground">{intl.formatMessage(mm.historyEarlier)}</p>
          )}
          <ol className={cn('flex flex-col gap-3 border-s-2 ps-4', round.current ? 'border-primary' : 'border-muted')}>
            {round.actions.map((action) => (
              <li key={action.seq} className="flex flex-col gap-0.5">
                <span className="flex flex-wrap gap-x-2 text-sm">
                  <span className="font-medium">{action.actor.name}</span>
                  <span>{intl.formatMessage(actionLabels[action.kind])}</span>
                </span>
                <span className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                  <time dateTime={action.occurredAt}>
                    {intl.formatDate(action.occurredAt, { dateStyle: 'medium', timeStyle: 'short' })}
                  </time>
                  {action.documentNumber ? (
                    <span>{intl.formatMessage(mmv.historyDocument, { number: action.documentNumber })}</span>
                  ) : null}
                </span>
                {action.remark ? <p className="whitespace-pre-wrap text-sm">{action.remark}</p> : null}
              </li>
            ))}
          </ol>
        </section>
      ))}
    </div>
  )
}
