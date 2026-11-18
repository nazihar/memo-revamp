import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { Info, Printer } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import {
  getMeetingAgendaOptions,
  getMeetingAgendaQueryKey,
  listAgendaRevisionsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { askToDiscuss, markAgendaSeen, withdrawDiscussionRequest } from '@/api/generated/sdk.gen'
import type {
  MemberAgendaResponse,
  MemberItemResponse,
  MemberPaperResponse,
  WhatChangedResponse,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { AgendaView } from './AgendaView'
import { pm, pmv } from './messages'
import { PaperViewer } from './PaperViewer'
import { WhatChangedDialog } from './WhatChanged'
import { lineName } from './words'
import './print.css'

const longDate = { day: 'numeric', month: 'long', year: 'numeric' } as const

/** The latest revision's query key: consent requests answer it. */
const latestKey = (meetingId: string) => getMeetingAgendaQueryKey({ path: { meetingId } })

/** Reloads "My meetings" (the cards' "changes since you last looked"). */
function refreshMine(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => (query.queryKey[0] as { _id?: string } | undefined)?._id === 'listMyMeetings',
  })
}

/** One sentence for a refused consent request or withdrawal. */
function refusal(intl: IntlShape, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(pm.failed)
  switch (problem.reason) {
    case 'ALREADY_ASKED':
      return intl.formatMessage(pm.ALREADY_ASKED)
    case 'NO_REQUEST':
      return intl.formatMessage(pm.NO_REQUEST)
    case 'WRONG_STATE':
      return intl.formatMessage(pm.WRONG_STATE)
  }
  if (problem.status === 403) return intl.formatMessage(pm.forbidden)
  if (problem.status === 404 || problem.status === 409) return intl.formatMessage(pm.WRONG_STATE)
  return intl.formatMessage(pm.failed)
}

/**
 * The agenda part of the meeting page (flow P): the revision the reader is on (the latest, or an earlier one they chose),
 * "What changed since you last looked" with "Show changes" (P2), the agenda itself, papers in the PDF viewer, and
 * consent requests. A revision shown is marked seen once, after it is on screen.
 */
export function MeetingAgenda({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const toast = useToast()
  const titleId = useId()
  const [chosen, setChosen] = useState<number>()
  const options = getMeetingAgendaOptions({
    path: { meetingId },
    ...(chosen === undefined ? {} : { query: { revision: chosen } }),
  })
  const agenda = useQuery(options)
  const data = agenda.data
  const latest = data ? data.revision == null || data.revision === data.latestRevision : true
  const revisions = useQuery({
    ...listAgendaRevisionsOptions({ path: { meetingId } }),
    enabled: (data?.latestRevision ?? 0) > 1,
  })

  // "What changed" as it was when the page opened: marking the revision seen empties it on the next read.
  const [changes, setChanges] = useState<WhatChangedResponse | null>(null)
  if (data?.whatChanged?.count && changes === null) setChanges(data.whatChanged)

  // Mark the revision shown as seen, once it is on screen (it only ever moves forward).
  const posted = useRef(0)
  const shown = data && !data.preview ? (data.revision ?? 0) : 0
  const lastSeen = data?.lastSeenRevision ?? 0
  useEffect(() => {
    if (!shown || shown <= lastSeen || shown <= posted.current) return
    posted.current = shown
    markAgendaSeen({ path: { meetingId }, body: { revision: shown }, throwOnError: true }).then(
      () => refreshMine(queryClient),
      () => {
        posted.current = 0
      },
    )
  }, [meetingId, shown, lastSeen, queryClient])

  const [paper, setPaper] = useState<MemberPaperResponse>()
  const [showChanges, setShowChanges] = useState(false)
  const [asking, setAsking] = useState<MemberItemResponse>()
  const [busyItem, setBusyItem] = useState<string>()

  if (agenda.isPending) return <LoadingState rows={4} />
  if (agenda.isError || !data) return <ErrorState onRetry={() => void agenda.refetch()} />

  const markOpened = (paperId: string) => {
    queryClient.setQueryData<MemberAgendaResponse>(options.queryKey, (current) =>
      current ? withPaperOpened(current, paperId) : current,
    )
  }

  const withdraw = async (item: MemberItemResponse) => {
    setBusyItem(item.id)
    try {
      const answer = await withdrawDiscussionRequest({
        path: { meetingId, itemId: item.id },
        throwOnError: true,
      })
      queryClient.setQueryData(latestKey(meetingId), answer.data)
      toast.show(intl.formatMessage(pm.withdrawn), 'success')
    } catch (problem) {
      await queryClient.invalidateQueries({ queryKey: latestKey(meetingId) })
      toast.show(refusal(intl, problem), 'error')
    } finally {
      setBusyItem(undefined)
    }
  }

  const revision = data.revision
  const published = data.publishedAt
    ? intl.formatDate(data.publishedAt, { ...longDate, timeZone: data.timeZone })
    : undefined
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={titleId} className="text-2xl font-semibold">
          {intl.formatMessage(pm.agenda)}
        </h2>
        {revision != null ? (
          <Button
            variant="outline"
            className="min-h-11 print:hidden"
            onClick={() => {
              window.print()
            }}
          >
            <Printer aria-hidden="true" />
            {intl.formatMessage(pm.print)}
          </Button>
        ) : null}
      </div>
      {revision == null ? (
        <p className="text-lg">{intl.formatMessage(pm.notPublished)}</p>
      ) : (
        <>
          {published ? (
            <p className="hidden print:block">
              {intl.formatMessage(pmv.printedRevision, { number: revision, date: published })}
            </p>
          ) : null}
          {data.latestRevision > 1 ? (
            <RevisionPicker
              published={revisions.data ?? []}
              value={revision}
              latest={data.latestRevision}
              onChange={(next) => {
                setChosen(next === data.latestRevision ? undefined : next)
              }}
            />
          ) : null}
          {latest ? (
            <div className="flex flex-col gap-2 rounded-md border bg-accent/40 p-4 text-lg print:hidden">
              <p className="flex gap-2">
                <Info aria-hidden="true" className="mt-1 size-5 shrink-0" />
                <span>
                  {intl.formatMessage(revision > 1 ? pmv.changedOn : pmv.publishedOn, {
                    number: revision,
                    date: published ?? '',
                  })}
                  {data.note ? <> {intl.formatMessage(pmv.note, { note: data.note })}</> : null}
                </span>
              </p>
              {changes?.count ? (
                <p className="flex flex-wrap items-center gap-x-3 gap-y-2 ps-7">
                  <span className="font-medium">{intl.formatMessage(pmv.changesSince, { count: changes.count })}</span>
                  <Button
                    variant="outline"
                    className="min-h-11"
                    onClick={() => {
                      setShowChanges(true)
                    }}
                  >
                    {intl.formatMessage(pm.showChanges)}
                  </Button>
                </p>
              ) : null}
            </div>
          ) : (
            <div className="flex flex-wrap items-center gap-3 rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-4 text-lg text-status-warn-fg print:hidden">
              <p>{intl.formatMessage(pmv.readingEarlier, { number: revision, date: published ?? '' })}</p>
              <Button
                variant="outline"
                className="min-h-11"
                onClick={() => {
                  setChosen(undefined)
                }}
              >
                {intl.formatMessage(pm.readLatest)}
              </Button>
            </div>
          )}
          <AgendaView
            agenda={data}
            latest={latest}
            actions={{
              onOpenPaper: (p) => {
                setPaper(p)
              },
              onAsk: (item) => {
                setAsking(item)
              },
              onWithdraw: (item) => void withdraw(item),
              ...(busyItem ? { busyItem } : {}),
            }}
          />
        </>
      )}
      {paper && revision != null ? (
        <PaperViewer
          meetingId={meetingId}
          revision={revision}
          paper={paper}
          onOpened={() => {
            markOpened(paper.id)
          }}
          onClose={() => {
            setPaper(undefined)
          }}
        />
      ) : null}
      {showChanges && changes ? (
        <WhatChangedDialog
          changes={changes}
          onClose={() => {
            setShowChanges(false)
          }}
        />
      ) : null}
      {asking ? (
        <AskDialog
          meetingId={meetingId}
          item={asking}
          onClose={() => {
            setAsking(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

/** The agenda with [paperId] marked as opened, wherever it is. */
function withPaperOpened(current: MemberAgendaResponse, paperId: string): MemberAgendaResponse {
  const item = (i: MemberItemResponse): MemberItemResponse => ({
    ...i,
    papers: i.papers.map((p) => (p.id === paperId ? { ...p, opened: true } : p)),
    subItems: i.subItems.map(item),
  })
  return { ...current, sections: current.sections.map((s) => ({ ...s, items: s.items.map(item) })) }
}

/** Earlier revisions of the agenda, the latest first. */
function RevisionPicker({
  published,
  value,
  latest,
  onChange,
}: {
  published: { number: number; publishedAt: string }[]
  value: number
  latest: number
  onChange: (revision: number) => void
}) {
  const intl = useIntl()
  const id = useId()
  const listed = published.length ? [...published].reverse() : [{ number: value, publishedAt: '' }]
  return (
    <div className="flex flex-wrap items-center gap-3 print:hidden">
      <label htmlFor={id} className="text-lg font-medium">
        {intl.formatMessage(pm.revision)}
      </label>
      <Select
        id={id}
        className="min-h-11 w-auto text-base"
        value={value}
        onChange={(e) => {
          onChange(Number(e.target.value))
        }}
      >
        {listed.map((r) => {
          const date = r.publishedAt ? intl.formatDate(r.publishedAt, longDate) : ''
          return (
            <option key={r.number} value={r.number}>
              {intl.formatMessage(r.number === latest ? pmv.revisionLatest : pmv.revisionOption, {
                number: r.number,
                date,
              })}
            </option>
          )
        })}
      </Select>
    </div>
  )
}

/**
 * "Ask for item 7 to be discussed separately?" (flow P1): the secretary and the chair are told, and the reader may
 * withdraw it until the meeting starts. One Idempotency-Key per dialog, so a retry is the same request.
 */
function AskDialog({ meetingId, item, onClose }: { meetingId: string; item: MemberItemResponse; onClose: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const toast = useToast()
  const [key] = useState(newIdempotencyKey)
  const submit = async (): Promise<string | undefined> => {
    try {
      const answer = await askToDiscuss({
        path: { meetingId, itemId: item.id },
        headers: { 'Idempotency-Key': key },
        throwOnError: true,
      })
      queryClient.setQueryData(latestKey(meetingId), answer.data)
      toast.show(intl.formatMessage(pm.asked), 'success')
      onClose()
      return undefined
    } catch (problem) {
      if (isProblem(problem) && (problem.status === 409 || problem.status === 404)) {
        await queryClient.invalidateQueries({ queryKey: latestKey(meetingId) })
      }
      return refusal(intl, problem)
    }
  }
  return (
    <FormDialog
      title={intl.formatMessage(pmv.askTitle, { number: item.number ?? lineName(intl, item) })}
      description={intl.formatMessage(pm.askExplain)}
      submitLabel={intl.formatMessage(pm.askSubmit)}
      cancelLabel={intl.formatMessage(pm.cancel)}
      onSubmit={submit}
      onClose={onClose}
    />
  )
}
