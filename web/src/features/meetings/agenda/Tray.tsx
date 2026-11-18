import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { listReadySubmissionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { SubmissionResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { TrayHandle } from './AgendaDnd'
import { submissionTitle } from './hooks'
import { ap, apv, purposeLabels } from './messages'
import { useAgenda } from './useAgenda'

/** "Ready for an agenda" (flow O2): accepted submissions not yet placed, due by this meeting first. */
export function Tray({ ask }: { ask: (s: SubmissionResponse) => void }) {
  const intl = useIntl()
  const { meeting, canAdd, editable } = useAgenda()
  const tray = useQuery(
    listReadySubmissionsOptions({
      path: { forumId: meeting.forumId },
      query: { on: meeting.localStartsAt.slice(0, 10) },
    }),
  )
  const list = tray.data ?? []
  return (
    <section aria-labelledby="agenda-tray" className="flex flex-col gap-3">
      <h2 id="agenda-tray" className="text-base font-semibold">
        {intl.formatMessage(apv.trayTitle, { count: list.length })}
      </h2>
      {tray.isPending ? <LoadingState rows={2} /> : null}
      {tray.isError ? <ErrorState onRetry={() => void tray.refetch()} /> : null}
      {tray.isSuccess && !list.length ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.trayEmpty)}</p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {list.map((s) => {
          const title = submissionTitle(s)
          return (
            <li key={s.id} className="flex gap-2 rounded-md border p-3">
              <TrayHandle submissionId={s.id} title={title} enabled={editable} />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                {s.memo?.reference ? <span className="text-sm text-muted-foreground">{s.memo.reference}</span> : null}
                <span dir="auto" className="font-medium">
                  {s.memo?.subject ?? title}
                </span>
                <span className="text-sm text-muted-foreground">
                  {[intl.formatMessage(purposeLabels[s.purpose]), s.presenter?.name].filter(Boolean).join(' · ')}
                </span>
                {s.triagedAt ? (
                  <span className="text-sm text-muted-foreground">
                    {intl.formatMessage(apv.acceptedOn, {
                      date: intl.formatDate(s.triagedAt, { dateStyle: 'medium' }),
                    })}
                  </span>
                ) : null}
                {canAdd ? (
                  <Button
                    variant="outline"
                    className="mt-1 w-fit"
                    aria-label={intl.formatMessage(apv.addToAgendaNamed, { title })}
                    onClick={() => {
                      ask(s)
                    }}
                  >
                    {intl.formatMessage(ap.addToAgenda)}
                  </Button>
                ) : null}
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
