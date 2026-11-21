import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CircleCheck, Send, TriangleAlert } from 'lucide-react'
import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import {
  getAgendaDraftOptions,
  getAgendaDraftQueryKey,
  getMeetingDeclarationRegisterOptions,
  getMeetingOptions,
  listClassificationLevelsOptions,
  listForumMeetingsOptions,
  listForumMembersOptions,
  listReadySubmissionsQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { startAgenda } from '@/api/generated/sdk.gen'
import type { MeetingResponse, SubmissionResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { fullName, meetingName, whenLine } from '../shared'
import { AgendaDnd } from './AgendaDnd'
import { AgendaList } from './AgendaList'
import { ChairAgreementLine, ChairPanel, LatePapers } from './Chair'
import { duration, timeIn, type Draft } from './draft'
import { ItemSheet } from './ItemSheet'
import { ap, apt, apv } from './messages'
import { PreviewAs } from './Preview'
import { agendaRefusal } from './problems'
import { PublishDialog } from './PublishDialog'
import { useMove, usePlacing } from './hooks'
import { Tray } from './Tray'
import {
  AgendaContext,
  useAgenda,
  useAgendaChange,
  type AgendaContextValue,
  type Person,
  type SheetTab,
} from './useAgenda'

/**
 * `/meetings/$meetingId/agenda`: the agenda builder (flow O) for the committee's secretaries, read by its chair (flow
 * Q). Before the agenda is started it offers the committee's standard agenda or a copy of an earlier meeting (O1).
 */
export function AgendaBuilderPage({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const meeting = useQuery(getMeetingOptions({ path: { meetingId } }))
  const draft = useQuery(getAgendaDraftOptions({ path: { meetingId } }))
  if (meeting.isPending || draft.isPending) return <LoadingState />
  if (meeting.isError || draft.isError) {
    return (
      <ErrorState
        title={draft.isError ? intl.formatMessage(ap.notOfficer) : undefined}
        onRetry={() => void Promise.all([meeting.refetch(), draft.refetch()])}
      />
    )
  }
  return <Builder meeting={meeting.data} draft={draft.data} />
}

function Builder({ meeting, draft }: { meeting: MeetingResponse; draft: Draft }) {
  const levels = useQuery(listClassificationLevelsOptions())
  const members = useQuery(listForumMembersOptions({ path: { forumId: meeting.forumId } }))
  // The meeting's declarations, for the quorum warnings on the rows (E04 §1.8, T7).
  const register = useQuery({
    ...getMeetingDeclarationRegisterOptions({ path: { meetingId: meeting.id } }),
    enabled: draft.started,
  })
  const { change } = useAgendaChange(meeting.id)
  const [announcement, setAnnouncement] = useState('')
  const [sheet, setSheet] = useState<{ itemId: string; tab: SheetTab }>()

  const audience = useMemo(() => {
    const people = new Map<string, Person>()
    for (const m of members.data ?? []) {
      if (m.person && m.state === 'IN_OFFICE') people.set(m.person.id, { id: m.person.id, name: m.person.name })
    }
    for (const g of meeting.guests) people.set(g.userId, { id: g.userId, name: g.name })
    for (const s of draft.sections) {
      for (const i of s.items.flatMap((x) => [x, ...x.subItems])) {
        for (const a of i.attendees) people.set(a.id, a)
      }
    }
    return [...people.values()].sort((a, b) => a.name.localeCompare(b.name))
  }, [members.data, meeting.guests, draft.sections])

  const announce = useCallback((text: string) => {
    // A repeated sentence is said again: clear first, then set.
    setAnnouncement('')
    window.setTimeout(() => {
      setAnnouncement(text)
    }, 50)
  }, [])
  const openSheet = useCallback((itemId: string, tab: SheetTab = 'details') => {
    setSheet({ itemId, tab })
  }, [])

  const value: AgendaContextValue = {
    meeting,
    draft,
    editable: draft.canEdit && draft.changes === 'ANY',
    canAdd: draft.canEdit && draft.changes !== 'NONE',
    audience,
    levels: levels.data ?? [],
    change,
    announce,
    openSheet,
    register: register.data,
  }

  return (
    <AgendaContext.Provider value={value}>
      <p role="status" aria-live="polite" className="sr-only">
        {announcement}
      </p>
      {draft.started ? <BuilderBody /> : <StartChooser />}
      {sheet ? (
        <ItemSheet
          key={`${sheet.itemId}-${sheet.tab}`}
          itemId={sheet.itemId}
          tab={sheet.tab}
          onClose={() => {
            setSheet(undefined)
          }}
        />
      ) : null}
    </AgendaContext.Provider>
  )
}

/** The page's heading, the meeting's times and the way back to the meeting. */
function Header({ children }: { children?: ReactNode }) {
  const intl = useIntl()
  const { meeting, draft } = useAgenda()
  const state = draft.currentRevision
    ? intl.formatMessage(apv.lastPublished, { revision: draft.currentRevision })
    : intl.formatMessage(ap.notPublished)
  return (
    <>
      <PageHeader
        className="mb-0"
        title={<span id="agenda-title">{intl.formatMessage(apv.pageTitle, { name: fullName(intl, meeting) })}</span>}
        status={draft.started ? <Badge tone={draft.currentRevision ? 'ok' : 'neutral'}>{state}</Badge> : undefined}
        description={
          <Link
            to="/meetings/$meetingId"
            params={{ meetingId: meeting.id }}
            className="underline-offset-4 hover:underline"
          >
            {intl.formatMessage(ap.backToMeeting)}
          </Link>
        }
        actions={children}
      />
      <p className="text-lg">{whenLine(intl, meeting)}</p>
    </>
  )
}

/** O1: "How would you like to start?" — the committee's standard agenda, or a copy of an earlier meeting. */
function StartChooser() {
  const intl = useIntl()
  const terms = useTerms()
  const queryClient = useQueryClient()
  const { meeting, draft } = useAgenda()
  const earlier = useQuery({
    ...listForumMeetingsOptions({
      path: { forumId: meeting.forumId },
      query: { status: ['DRAFT', 'SCHEDULED', 'IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED'] },
    }),
    enabled: draft.canEdit,
  })
  const others = (earlier.data?.items ?? [])
    .filter((m) => m.id !== meeting.id)
    .sort((a, b) => b.startsAt.localeCompare(a.startsAt))
  const [from, setFrom] = useState<'TEMPLATE' | 'MEETING'>('TEMPLATE')
  const [source, setSource] = useState('')
  const [refusal, setRefusal] = useState<string>()
  const [busy, setBusy] = useState(false)
  const chosen = (source === '' ? others[0]?.id : source) ?? ''
  const standard = (draft.template?.sections ?? []).flatMap((s) => [s.title, ...(s.items ?? []).map((i) => i.title)])

  const start = async () => {
    setBusy(true)
    setRefusal(undefined)
    try {
      const { data } = await startAgenda({
        path: { meetingId: meeting.id },
        headers: { 'Idempotency-Key': newIdempotencyKey() },
        body: from === 'TEMPLATE' ? { from } : { from, meetingId: chosen },
        throwOnError: true,
      })
      queryClient.setQueryData(getAgendaDraftQueryKey({ path: { meetingId: meeting.id } }), data)
    } catch (problem) {
      setRefusal(agendaRefusal(intl, terms, problem))
      await queryClient.invalidateQueries({ queryKey: getAgendaDraftQueryKey({ path: { meetingId: meeting.id } }) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="agenda-title" className="flex max-w-3xl flex-col gap-5">
      <Header />
      {!draft.canEdit ? (
        <p>{intl.formatMessage(ap.notStartedYet)}</p>
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault()
            void start()
          }}
        >
          <fieldset className="flex flex-col gap-3">
            <legend className="mb-2 text-lg font-semibold">{intl.formatMessage(ap.howToStart)}</legend>
            <label className="flex min-h-11 items-start gap-3">
              <input
                type="radio"
                name="from"
                className="mt-1 size-4 accent-primary"
                checked={from === 'TEMPLATE'}
                onChange={() => {
                  setFrom('TEMPLATE')
                }}
              />
              <span className="flex flex-col gap-1">
                <span className="font-medium">{intl.formatMessage(apt.fromTemplate, terms)}</span>
                {standard.length ? <span className="text-sm text-muted-foreground">{standard.join(' · ')}</span> : null}
              </span>
            </label>
            <label className="flex min-h-11 items-start gap-3">
              <input
                type="radio"
                name="from"
                className="mt-1 size-4 accent-primary"
                checked={from === 'MEETING'}
                disabled={!others.length}
                onChange={() => {
                  setFrom('MEETING')
                }}
              />
              <span className="flex flex-col gap-1">
                <span className="font-medium">{intl.formatMessage(ap.fromMeeting)}</span>
                <span className="text-sm text-muted-foreground">
                  {intl.formatMessage(others.length ? ap.fromMeetingHint : ap.noEarlier)}
                </span>
              </span>
            </label>
            {from === 'MEETING' ? (
              <div className="ms-7 flex flex-col gap-1.5">
                <label htmlFor="agenda-source" className="text-sm font-medium">
                  {intl.formatMessage(ap.copyFrom)}
                </label>
                <Select
                  id="agenda-source"
                  className="w-80 max-w-full"
                  value={chosen}
                  onChange={(e) => {
                    setSource(e.target.value)
                  }}
                >
                  {others.map((m) => (
                    <option key={m.id} value={m.id}>
                      {intl.formatMessage(apv.meetingOption, {
                        name: meetingName(m),
                        date: intl.formatDate(m.startsAt, { dateStyle: 'medium', timeZone: m.timeZone }),
                      })}
                    </option>
                  ))}
                </Select>
              </div>
            ) : null}
          </fieldset>
          {refusal ? (
            <p
              role="alert"
              className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
            >
              {refusal}
            </p>
          ) : null}
          <div>
            <Button type="submit" disabled={busy}>
              {intl.formatMessage(ap.start)}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}

function BuilderBody() {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const { meeting, draft, canAdd } = useAgenda()
  const { move, refusal: moveRefusal } = useMove()
  const placing = usePlacing()
  const [publishing, setPublishing] = useState(false)

  const placeDropped = (submissionId: string, target: { sectionId: string; position: number | null }) => {
    const tray = queryClient.getQueryData<SubmissionResponse[]>(
      listReadySubmissionsQueryKey({
        path: { forumId: meeting.forumId },
        query: { on: meeting.localStartsAt.slice(0, 10) },
      }),
    )
    const submission = tray?.find((s) => s.id === submissionId)
    if (submission) placing.ask(submission, target)
  }

  const refusal = moveRefusal ?? placing.refusal
  return (
    <section aria-labelledby="agenda-title" className="flex flex-col gap-5">
      <Header>
        {draft.canEdit && draft.changes !== 'NONE' ? (
          <Button
            onClick={() => {
              setPublishing(true)
            }}
          >
            <Send aria-hidden="true" />
            {intl.formatMessage(apv.publishRevision, { revision: draft.currentRevision + 1 })}
          </Button>
        ) : null}
      </Header>
      <div className="flex flex-col gap-2 rounded-md border bg-muted/30 p-3">
        <DeadlineLine />
        <ChairAgreementLine />
        {draft.currentRevision > 0 ? (
          <p className="text-sm">{intl.formatMessage(draft.dirty ? ap.dirty : ap.clean)}</p>
        ) : null}
        <StateNote />
      </div>
      <CopyReport />
      <ChairPanel />
      <LatePapers />
      <PreviewAs />
      {refusal ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
        >
          {refusal}
        </p>
      ) : null}
      <AgendaDnd draft={draft} onMove={(body) => void move(body)} onPlace={placeDropped}>
        <div
          className={canAdd ? 'grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]' : 'flex flex-col'}
        >
          <div className="flex flex-col gap-4">
            <AgendaList onMove={(body) => void move(body)} />
            <TotalLine />
          </div>
          {/* The tray sticks below the sticky top bar (3.5rem), not under it. */}
          {canAdd ? (
            <aside className="rounded-md border p-3 lg:sticky lg:top-18">
              <Tray
                ask={(s) => {
                  placing.ask(s)
                }}
              />
            </aside>
          ) : null}
        </div>
      </AgendaDnd>
      {placing.dialog}
      {publishing ? (
        <PublishDialog
          onClose={() => {
            setPublishing(false)
          }}
        />
      ) : null}
    </section>
  )
}

/** "Papers due 5 Dec 2026", whether the deadline has passed and what the committee does with late papers. */
function DeadlineLine() {
  const intl = useIntl()
  const { draft } = useAgenda()
  if (!draft.paperDeadline) return null
  const date = intl.formatDate(`${draft.paperDeadline}T12:00:00Z`, { dateStyle: 'medium', timeZone: 'UTC' })
  const first = draft.revisions[0]
  const onTime = first ? first.publishedAt.slice(0, 10) <= draft.paperDeadline : false
  return (
    <p className="flex flex-wrap items-center gap-x-2 text-sm">
      <span className="font-medium">{intl.formatMessage(apv.papersDue, { date })}</span>
      {onTime ? (
        <span className="flex items-center gap-1">
          <CircleCheck aria-hidden="true" className="size-4 text-status-ok-fg" />
          {intl.formatMessage(ap.publishedOnTime)}
        </span>
      ) : draft.deadlinePassed ? (
        <span className="flex items-center gap-1">
          <TriangleAlert aria-hidden="true" className="size-4 text-status-warn-fg" />
          {intl.formatMessage(ap[`deadlinePassed${draft.lateMode}`])}
        </span>
      ) : null}
    </p>
  )
}

/** What the draft accepts now: read-only for the chair; only tabled additions while in progress; frozen after. */
function StateNote() {
  const intl = useIntl()
  const { draft } = useAgenda()
  const text = !draft.canEdit
    ? intl.formatMessage(ap.readOnly)
    : draft.changes === 'NONE'
      ? intl.formatMessage(ap.frozen)
      : draft.changes === 'TABLED_ONLY'
        ? intl.formatMessage(ap.tabledOnly)
        : undefined
  return text ? <p className="text-sm">{text}</p> : null
}

/** After a copy: restrictions that named people not on this meeting, and items left out (design E03 §1.7). */
function CopyReport() {
  const intl = useIntl()
  const { draft } = useAgenda()
  const copied = draft.copied
  if (!copied || (!copied.dropped.length && !copied.skipped.length)) return null
  return (
    <div role="status" className="flex flex-col gap-1 rounded-md border p-3 text-sm">
      {copied.dropped.length ? (
        <p>
          {intl.formatMessage(apv.copyDropped, {
            count: copied.dropped.length,
            names: intl.formatList(copied.dropped.map((p) => p.name)),
          })}
        </p>
      ) : null}
      {copied.skipped.length ? (
        <p>
          {intl.formatMessage(apv.copySkipped, {
            count: copied.skipped.length,
            titles: intl.formatList(copied.skipped),
          })}
        </p>
      ) : null}
    </div>
  )
}

/** "Total 1 h 30 min · ends 11:30 · planned end 13:00 ✓", or the overrun in words. */
function TotalLine() {
  const intl = useIntl()
  const { draft } = useAgenda()
  const ends = timeIn(intl.locale, draft.plannedEndsAt, draft.timeZone)
  const planned = timeIn(intl.locale, draft.endsAt, draft.timeZone)
  const over = Math.round((Date.parse(draft.plannedEndsAt) - Date.parse(draft.endsAt)) / 60_000)
  return (
    <p
      className={
        draft.overrun
          ? 'flex items-center gap-2 rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-status-warn-fg'
          : 'flex items-center gap-2 text-sm'
      }
    >
      {draft.overrun ? (
        <>
          <TriangleAlert aria-hidden="true" className="size-4 shrink-0" />
          {intl.formatMessage(apv.overrun, { ends, minutes: over, total: duration(intl.locale, draft.totalMinutes) })}
        </>
      ) : (
        <>
          {intl.formatMessage(apv.total, { total: duration(intl.locale, draft.totalMinutes), ends, planned })}
          <CircleCheck aria-hidden="true" className="size-4 text-status-ok-fg" />
        </>
      )}
    </p>
  )
}
