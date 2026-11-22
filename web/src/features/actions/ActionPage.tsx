import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import {
  getActionEntriesQueryKey,
  getActionOptions,
  getActionQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import { startAction } from '@/api/generated/sdk.gen'
import type { ActionResponse } from '@/api/generated/types.gen'
import type { Problem } from '@/api/http'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { ActionDialog, type ActionDialogKind } from './ActionDialogs'
import { FollowUpMemos, LinkMemoDialog, RaiseMemoDialog } from './FollowUpMemos'
import { am } from './messages'
import { actionRefusal, staleRefusal } from './problems'
import { RagBadge } from './RagBadge'
import { Timeline } from './Timeline'
import { overrideWords } from './timeline-words'
import { cancelWord, day, ownerWords, referenceWords, statusWord } from './words'

type Open = ActionDialogKind | 'raiseMemo' | 'linkMemo'

/**
 * `/actions/{id}`, the action page (flow AL, design §1.6–§1.11): what is asked, by whom and by when in words; where it
 * comes from as far as the viewer may see (a recused owner gets the item's number only, with no link); "Post an update",
 * "I have started", "Ask for a new date", "Raise a memo", "Link an existing memo", "Mark as done" (and "Verify" for the
 * verifier), each shown only when the server says the viewer may; follow-up memos; the timeline. After each change the
 * page says what happened in a live region and moves focus there. An action the viewer may not see is "not there" (404).
 */
export function ActionPage({ actionId }: { actionId: string }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const action = useQuery(getActionOptions({ path: { actionId } }))
  const [open, setOpen] = useState<Open>()
  // What the page last said, and a count that moves focus to it each time (after the dialog returned focus to its opener).
  const [said, setSaid] = useState({ text: '', times: 0 })
  const [starting, setStarting] = useState(false)
  const saidRef = useRef<HTMLParagraphElement>(null)
  const terms = useTerms()
  useEffect(() => {
    if (said.times > 0) saidRef.current?.focus()
  }, [said])
  const announce = (text: string) => {
    setSaid((s) => ({ text, times: s.times + 1 }))
  }

  const reload = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getActionQueryKey({ path: { actionId } }) }),
      queryClient.invalidateQueries({ queryKey: getActionEntriesQueryKey({ path: { actionId } }) }),
      queryClient.invalidateQueries({ queryKey: [{ _id: 'getMyActions' }] }),
    ])
  }
  /** A change went through: show the action as it is now and say so. */
  const done = (next: ActionResponse, sentence: string) => {
    setOpen(undefined)
    queryClient.setQueryData(getActionQueryKey({ path: { actionId } }), next)
    void reload()
    announce(sentence)
  }
  const close = () => {
    setOpen(undefined)
  }

  if (action.isPending) return <LoadingState />
  if (action.isError) {
    return (action.error as Problem | undefined)?.status === 404 ? (
      <EmptyState title={intl.formatMessage(am.notFound)} />
    ) : (
      <ErrorState onRetry={() => void action.refetch()} />
    )
  }
  const a = action.data
  const can = a.can

  const start = async () => {
    setStarting(true)
    try {
      const next = await startAction({
        path: { actionId: a.id },
        headers: { 'Idempotency-Key': newIdempotencyKey(), 'If-Match': `"${String(a.version)}"` },
        body: {},
        throwOnError: true,
      })
      done(next.data, intl.formatMessage(am.startedDone))
    } catch (problem) {
      if (staleRefusal(problem)) void reload()
      announce(actionRefusal(intl, terms, problem))
    } finally {
      setStarting(false)
    }
  }

  const offer = (when: boolean, key: Open | 'start', label: string, primary = false) =>
    when ? [{ key, label, primary }] : []
  // One primary button: "Mark as done" when the owner may, else "Post an update" (principle 6).
  const buttons = [
    ...offer(can.update, 'update', intl.formatMessage(am.postUpdate), !can.markDone),
    ...offer(can.start, 'start', intl.formatMessage(am.started)),
    ...offer(can.requestDue, 'requestDue', intl.formatMessage(am.askNewDate)),
    ...offer(can.changeDue && !can.requestDue, 'changeDue', intl.formatMessage(am.changeDate)),
    ...offer(can.raiseMemo, 'raiseMemo', intl.formatMessage(am.raiseMemo)),
    ...offer(can.linkMemo, 'linkMemo', intl.formatMessage(am.linkMemo)),
    ...offer(can.markDone, 'done', intl.formatMessage(am.markDone), true),
    ...offer(can.verify, 'verify', intl.formatMessage(am.verify), true),
  ]
  const press = (key: Open | 'start') => {
    if (key === 'start') void start()
    else setOpen(key)
  }

  return (
    <article aria-labelledby="action-title" className="flex max-w-4xl flex-col gap-6">
      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-lg text-muted-foreground">
            {intl.formatMessage(am.referenceCommittee, {
              reference: referenceWords(intl, a.reference),
              committee: a.committee,
            })}
          </p>
          <RagBadge rag={a.rag} size="lg" />
        </div>
        <h1 id="action-title" className="text-2xl font-semibold md:text-3xl">
          {a.title}
        </h1>
        {a.rag.override ? <p className="text-muted-foreground">{overrideWords(intl, a.rag.override)}</p> : null}
        {a.priority === 'HIGH' ? <Badge tone="warn">{intl.formatMessage(am.priorityHigh)}</Badge> : null}
      </header>

      {a.viewerRecused ? (
        <p
          role="note"
          className="rounded-md border border-status-warn-fg/40 bg-status-warn-bg px-4 py-3 text-status-warn-fg"
        >
          {intl.formatMessage(am.recusedNote)}
        </p>
      ) : null}

      <Facts action={a} />

      <p
        ref={saidRef}
        tabIndex={-1}
        role="status"
        className="text-lg font-medium focus-visible:outline-2 focus-visible:outline-ring empty:hidden"
      >
        {said.text}
      </p>

      <section aria-labelledby="action-next" className="flex flex-col gap-3 border-y py-4">
        <h2 id="action-next" className="sr-only">
          {intl.formatMessage(am.whatNext)}
        </h2>
        {buttons.length ? (
          <div className="flex flex-wrap gap-2">
            {buttons.map((b) => (
              <Button
                key={b.key}
                variant={b.primary ? 'default' : 'outline'}
                className="min-h-11 text-base"
                disabled={b.key === 'start' && starting}
                onClick={() => {
                  press(b.key)
                }}
              >
                {b.label}
              </Button>
            ))}
          </div>
        ) : (
          <p className="text-muted-foreground">
            {a.status === 'DONE_PENDING'
              ? intl.formatMessage(am.verifyLater, terms)
              : intl.formatMessage(am.nothingToDo)}
          </p>
        )}
      </section>

      <FollowUpMemos action={a} />
      <Timeline action={a} />

      {open === 'raiseMemo' ? (
        <RaiseMemoDialog action={a} onClose={close} />
      ) : open === 'linkMemo' ? (
        <LinkMemoDialog action={a} onClose={close} onDone={done} onStale={() => void reload()} />
      ) : open ? (
        <ActionDialog kind={open} action={a} onClose={close} onDone={done} onStale={() => void reload()} />
      ) : null}
    </article>
  )
}

/** One fact of the action: a term and its description (a definition list, read in order). */
function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:gap-4">
      <dt className="shrink-0 font-medium text-muted-foreground sm:w-48">{term}</dt>
      <dd className="min-w-0 flex-1">{children}</dd>
    </div>
  )
}

/** Owner, also involved, due (with where it started), source, what the committee asked, details and where it stands. */
function Facts({ action: a }: { action: ActionResponse }) {
  const intl = useIntl()
  const terms = useTerms()
  const sourceId = useId()
  const owner = ownerWords(intl, a.owner)
  const source = a.source
  // The item's title only when the server gives it (the title rule); a recused owner gets the number only, with no link.
  const from = source.itemTitle
    ? intl.formatMessage(am.fromWithTitle, { label: source.label, title: source.itemTitle })
    : source.label
  const status = a.stepName
    ? intl.formatMessage(am.statusStep, { status: statusWord(intl, a.status), step: a.stepName })
    : statusWord(intl, a.status)
  return (
    <dl className="flex flex-col gap-3 text-lg">
      {owner ? <Fact term={intl.formatMessage(am.owner)}>{owner}</Fact> : null}
      {a.contributors.length ? (
        <Fact term={intl.formatMessage(am.also)}>
          {intl.formatList(
            a.contributors.map((c) => c.name),
            { type: 'conjunction' },
          )}
        </Fact>
      ) : null}
      <Fact term={intl.formatMessage(am.dueLabel)}>
        <span>{a.dueDate ? day(intl, a.dueDate) : intl.formatMessage(am.noDue)}</span>
        {a.dueDate && a.dueRevisions > 0 && a.originalDueDate && a.originalDueDate !== a.dueDate ? (
          <span className="block text-base text-muted-foreground">
            {intl.formatMessage(am.dueOriginally, { original: day(intl, a.originalDueDate), count: a.dueRevisions })}
          </span>
        ) : null}
        {a.pendingRequest ? (
          <span className="mt-1 block text-base">
            {intl.formatMessage(am.pendingRequest, {
              date: day(intl, a.pendingRequest.newDate),
              current: a.dueDate ? day(intl, a.dueDate) : intl.formatMessage(am.noDue),
            })}
            <span className="block text-muted-foreground">
              {intl.formatMessage(am.pendingRequestBy, {
                name: a.pendingRequest.requestedBy.name,
                reason: a.pendingRequest.reason,
              })}
            </span>
          </span>
        ) : null}
      </Fact>
      <Fact term={intl.formatMessage(am.from)}>
        <span id={sourceId}>
          {source.note ? intl.formatMessage(am.withNote, { label: from, note: source.note }) : from}
        </span>
        {source.meetingId && source.itemTitle && !a.viewerRecused ? (
          <Link
            to="/meetings/$meetingId"
            params={{ meetingId: source.meetingId }}
            aria-describedby={sourceId}
            className="ms-3 inline-flex min-h-11 items-center text-base text-primary underline underline-offset-4"
          >
            {intl.formatMessage(am.openSourceMeeting)}
          </Link>
        ) : null}
      </Fact>
      {a.context ? (
        <Fact term={intl.formatMessage(am.asked, terms)}>
          <span className="whitespace-pre-line">{intl.formatMessage(am.quoted, { text: a.context })}</span>
        </Fact>
      ) : null}
      {a.description ? (
        <Fact term={intl.formatMessage(am.details)}>
          <span className="whitespace-pre-line">{a.description}</span>
        </Fact>
      ) : null}
      <Fact term={intl.formatMessage(am.status)}>
        <span>{status}</span>
        {a.closedAt && a.status === 'CLOSED' ? (
          <span className="block text-base text-muted-foreground">
            {intl.formatMessage(am.closedOn, { date: intl.formatDate(a.closedAt, { dateStyle: 'long' }) })}
          </span>
        ) : null}
        {a.status === 'CANCELLED' && a.cancelKind ? (
          <span className="block text-base text-muted-foreground">
            {intl.formatMessage(am.cancelledWhy, {
              kind: cancelWord(intl, a.cancelKind),
              reason: a.cancelReason ?? '',
            })}
          </span>
        ) : null}
        {a.reopenCount > 0 ? (
          <span className="block text-base text-muted-foreground">
            {intl.formatMessage(am.reopened, { count: a.reopenCount })}
          </span>
        ) : null}
      </Fact>
    </dl>
  )
}
