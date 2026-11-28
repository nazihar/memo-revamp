import { useQuery } from '@tanstack/react-query'
import { useId } from 'react'
import { useIntl } from 'react-intl'
import { getActionEntriesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ActionResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { useTerms } from '@/features/terms/terms'
import { EvidenceLinks } from './Evidence'
import { am } from './messages'
import { headlineOf } from './timeline-words'

/**
 * The action's timeline (design §1.6, flow AL), newest first: updates with what was written, how it is going and their
 * evidence; date changes with the old and new date; owner changes, the colour set by hand, verification, memos and the
 * source's corrections. Entries are never edited: a correction is its own entry, marked as one.
 */
export function Timeline({ action }: { action: ActionResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const headingId = useId()
  // The page's version is in the key's neighbourhood: every change of the action reloads the timeline (invalidated).
  const entries = useQuery(getActionEntriesOptions({ path: { actionId: action.id } }))
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-xl font-semibold">
        {intl.formatMessage(am.timeline)}
      </h2>
      {entries.isPending ? (
        <LoadingState rows={3} />
      ) : entries.isError ? (
        <ErrorState onRetry={() => void entries.refetch()} />
      ) : entries.data.entries.length === 0 ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.timelineEmpty)}</p>
      ) : (
        <ol className="flex flex-col divide-y border-y">
          {entries.data.entries.map((e) => (
            <li key={e.seq} className="flex flex-col gap-1 py-3 sm:flex-row sm:gap-4">
              <time dateTime={e.at} className="shrink-0 text-sm tabular-nums text-muted-foreground sm:w-28">
                {intl.formatDate(e.at, { day: 'numeric', month: 'short', year: 'numeric' })}
              </time>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <p className="font-medium">{headlineOf(intl, terms, e, action)}</p>
                {e.corrects ? (
                  <p className="text-sm text-muted-foreground">{intl.formatMessage(am.correction)}</p>
                ) : null}
                {e.text ? (
                  <p dir="auto" className="whitespace-pre-line">
                    {intl.formatMessage(am.quoted, { text: e.text })}
                  </p>
                ) : null}
                <EvidenceLinks files={e.files} />
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
