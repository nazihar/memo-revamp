import { Link } from '@tanstack/react-router'
import { TriangleAlert } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { makeMinutesDraft } from '@/api/generated/sdk.gen'
import type { MinutesCopySetResponse, StructureProblemResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { CommentsPanel } from './CommentsPanel'
import { CopyViewer } from './CopyViewer'
import { useMinutesWrite } from './data'
import {
  AskDialog,
  CirculateDialog,
  CopyForDialog,
  DeadlineDialog,
  FillDialog,
  OnBehalfDialog,
  ReasonDialog,
  RemakeDialog,
  SendDialog,
} from './dialogs'
import { mn, mnv } from './messages'
import {
  copyLabel,
  day,
  itemName,
  moment,
  numbers,
  refusal,
  weekday,
  workFailure,
  type Minutes,
  type MinutesItem,
} from './words'

type Open =
  | { kind: 'remake' | 'send' | 'behalf' | 'withdraw' | 'end' | 'deadline' | 'ask' | 'copies' | 'own' }
  | { kind: 'circulate'; noReviewer: boolean }
  | { kind: 'fill'; item: MinutesItem; restore: boolean }
  | { kind: 'copy'; copy: MinutesCopySetResponse }

const big = 'min-h-11 text-base'

/**
 * The minutes page for the committee's secretaries (flows AD, AE on the chair's behalf, AF4, AG): per stage what is
 * ready, what is wrong and what to do next. While the draft is being made or a saved version checked, a live region says
 * so (the page reads itself again every two seconds meanwhile). After each step its result is said in the same region,
 * which takes the focus, so a screen reader hears it and a keyboard user is back on the page.
 */
export function SecretaryPanel({
  page,
  items,
  meetingName,
  timeZone,
}: {
  page: Minutes
  items: MinutesItem[]
  meetingName: string
  timeZone?: string
}) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [open, setOpen] = useState<Open>()
  const [said, setSaid] = useState<string>()
  const [refused, setRefused] = useState<string>()
  const [busy, setBusy] = useState(false)
  const status = useRef<HTMLParagraphElement>(null)
  const close = () => {
    setOpen(undefined)
  }
  const done = (text: string) => {
    setOpen(undefined)
    setRefused(undefined)
    setSaid(text)
    requestAnimationFrame(() => status.current?.focus())
  }
  const byNumber = new Map(items.map((i) => [i.itemId, i]))
  const working = page.work?.state === 'PENDING'
  const draft = page.draft

  const makeDraft = async () => {
    setBusy(true)
    setRefused(undefined)
    try {
      await write(['make'], (key) =>
        makeMinutesDraft({
          path: { meetingId: page.meetingId },
          headers: { 'Idempotency-Key': key },
          body: {},
          throwOnError: true,
        }),
      )
      done(intl.formatMessage(mn.making))
    } catch (problem) {
      setRefused(refusal(intl, problem))
    } finally {
      setBusy(false)
    }
  }

  // What the live region says: the job's progress first, then the last step's result.
  const progress = working
    ? page.work?.kind === 'ITEM' && page.work.itemId
      ? intl.formatMessage(mnv.filling, { number: byNumber.get(page.work.itemId)?.number ?? '?' })
      : intl.formatMessage(mn.making)
    : page.structure.pending
      ? intl.formatMessage(mn.checking)
      : said

  return (
    <div className="flex flex-col gap-5 text-lg">
      <p ref={status} role="status" tabIndex={-1} className="outline-none">
        {progress}
      </p>
      {page.viewerRecused ? <p className="rounded-md border p-3">{intl.formatMessage(mn.recusedSecretary)}</p> : null}
      {page.work?.state === 'FAILED' ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-destructive">
          {workFailure(intl, page.work.error)}
        </p>
      ) : null}
      {refused ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-destructive">
          {refused}
        </p>
      ) : null}

      <StageLine
        page={page}
        timeZone={timeZone}
        onDeadline={() => {
          setOpen({ kind: 'deadline' })
        }}
      />

      {page.state === 'DRAFT' && !draft && !page.viewerRecused && !working ? (
        <div className="flex flex-col gap-3">
          <p>{intl.formatMessage(mn.noDraft)}</p>
          {page.mayGenerate ? (
            <div>
              <Button className={big} disabled={busy} onClick={() => void makeDraft()}>
                {intl.formatMessage(mn.makeDraft)}
              </Button>
            </div>
          ) : null}
        </div>
      ) : null}
      {page.state === 'DRAFT' && page.undecided.length ? (
        <p className="flex gap-2 rounded-md bg-status-warn-bg p-3 text-status-warn-fg">
          <TriangleAlert aria-hidden="true" className="mt-1 size-5 shrink-0" />
          {intl.formatMessage(mnv.undecided, { count: page.undecided.length, items: numbers(intl, page.undecided) })}
        </p>
      ) : null}
      {draft ? (
        <p className="font-medium">
          {draft.savedBy?.name
            ? intl.formatMessage(mnv.draftLineBy, {
                number: draft.number,
                when: moment(intl, draft.savedAt, timeZone),
                name: draft.savedBy.name,
              })
            : intl.formatMessage(mnv.draftLine, { number: draft.number, when: moment(intl, draft.savedAt, timeZone) })}
        </p>
      ) : null}
      {page.lastReview ? <LastReview page={page} timeZone={timeZone} /> : null}
      <StructureBanner
        page={page}
        items={byNumber}
        onRestore={(item) => {
          setOpen({ kind: 'fill', item, restore: true })
        }}
      />
      {page.structure.trackedChanges || page.structure.comments ? (
        <p className="rounded-md bg-status-warn-bg p-3 text-status-warn-fg">
          {intl.formatMessage(mnv.marks, {
            changes: page.structure.trackedChanges,
            comments: page.structure.comments,
          })}
        </p>
      ) : null}
      {page.state === 'CIRCULATED' && page.mayEdit ? (
        <p className="text-muted-foreground">{intl.formatMessage(mn.editsUpdate)}</p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {page.mayEdit && page.editFileId ? (
          <Button asChild variant={page.state === 'DRAFT' ? 'default' : 'outline'} className={big}>
            <Link to="/meetings/$meetingId/minutes/edit" params={{ meetingId: page.meetingId }}>
              {intl.formatMessage(mn.openEditor)}
            </Link>
          </Button>
        ) : null}
        {page.copies.length ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'copies' })
            }}
          >
            {intl.formatMessage(mn.seeCopyFor)}
          </Button>
        ) : null}
        {page.mayReadCopy && page.state !== 'APPROVED' ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'own' })
            }}
          >
            {intl.formatMessage(mn.readMyCopy)}
          </Button>
        ) : null}
        {page.maySendForReview ? (
          <Button
            className={big}
            onClick={() => {
              setOpen({ kind: 'send' })
            }}
          >
            {intl.formatMessage(mn.sendToChair)}
          </Button>
        ) : null}
        {page.mayCirculate ? (
          <Button
            className={big}
            onClick={() => {
              setOpen({ kind: 'circulate', noReviewer: !page.agreed })
            }}
          >
            {intl.formatMessage(mn.circulate)}
          </Button>
        ) : null}
        {page.mayReview && page.viewerAccess === 'MANAGER' && page.state === 'CHAIR_REVIEW' ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'behalf' })
            }}
          >
            {intl.formatMessage(mn.recordAgreement)}
          </Button>
        ) : null}
        {page.state === 'CIRCULATED' && !page.viewerRecused ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'ask' })
            }}
          >
            {intl.formatMessage(mn.askOthers)}
          </Button>
        ) : null}
        {page.mayEndCirculation ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'end' })
            }}
          >
            {intl.formatMessage(mn.endEarly)}
          </Button>
        ) : null}
        {page.mayWithdraw ? (
          <Button
            variant="outline"
            className={big}
            onClick={() => {
              setOpen({ kind: 'withdraw' })
            }}
          >
            {intl.formatMessage(mn.withdraw)}
          </Button>
        ) : null}
      </div>

      {page.state === 'DRAFT' && draft && page.mayGenerate ? (
        <FillItems
          items={items}
          onFill={(item) => {
            setOpen({ kind: 'fill', item, restore: false })
          }}
          onRemake={() => {
            setOpen({ kind: 'remake' })
          }}
        />
      ) : null}

      {page.state === 'CIRCULATED' || page.state === 'AWAITING_CHAIR' ? (
        <CommentsPanel page={page} respond={page.state === 'CIRCULATED' && !page.viewerRecused} timeZone={timeZone} />
      ) : null}

      {open?.kind === 'remake' ? <RemakeDialog page={page} onDone={done} onClose={close} /> : null}
      {open?.kind === 'fill' ? (
        <FillDialog page={page} item={open.item} restore={open.restore} onDone={done} onClose={close} />
      ) : null}
      {open?.kind === 'send' ? (
        <SendDialog
          page={page}
          onDone={done}
          onClose={close}
          onNoReviewer={() => {
            setOpen({ kind: 'circulate', noReviewer: true })
          }}
        />
      ) : null}
      {open?.kind === 'circulate' ? (
        <CirculateDialog page={page} noReviewer={open.noReviewer} onDone={done} onClose={close} />
      ) : null}
      {open?.kind === 'behalf' ? <OnBehalfDialog page={page} onDone={done} onClose={close} /> : null}
      {open?.kind === 'withdraw' || open?.kind === 'end' ? (
        <ReasonDialog page={page} kind={open.kind} onDone={done} onClose={close} />
      ) : null}
      {open?.kind === 'deadline' ? <DeadlineDialog page={page} onDone={done} onClose={close} /> : null}
      {open?.kind === 'ask' ? <AskDialog page={page} items={items} onDone={done} onClose={close} /> : null}
      {open?.kind === 'copies' ? (
        <CopyForDialog
          copies={page.copies}
          onShow={(copy) => {
            setOpen({ kind: 'copy', copy })
          }}
          onClose={close}
        />
      ) : null}
      {open?.kind === 'copy' && open.copy.readers[0] ? (
        <CopyViewer
          meetingId={page.meetingId}
          source={{ kind: 'preview', userId: open.copy.readers[0].id }}
          title={`${intl.formatMessage(mnv.copyOf, {
            names: intl.formatList(
              open.copy.readers.map((r) => r.name),
              { type: 'conjunction' },
            ),
          })} — ${copyLabel(intl, open.copy)}`}
          onClose={close}
        />
      ) : null}
      {open?.kind === 'own' ? (
        <CopyViewer
          meetingId={page.meetingId}
          source={{ kind: 'own' }}
          title={intl.formatMessage(mnv.draftOf, { meeting: meetingName })}
          onClose={close}
        />
      ) : null}
    </div>
  )
}

/** Where the minutes stand for the secretary: with whom, until when, approved where. */
function StageLine({ page, timeZone, onDeadline }: { page: Minutes; timeZone?: string; onDeadline: () => void }) {
  const intl = useIntl()
  const lines: string[] = []
  if (page.state === 'CHAIR_REVIEW' && page.reviewer) {
    lines.push(intl.formatMessage(mnv.withReviewer, { name: page.reviewer.name }))
  }
  if (page.state === 'CIRCULATED' && page.circulatedAt && page.commentsUntil) {
    lines.push(
      intl.formatMessage(mnv.circulatedOn, {
        date: day(intl, page.circulatedAt, timeZone),
        until: weekday(intl, page.commentsUntil, timeZone),
      }),
    )
  }
  if (page.state === 'CIRCULATED' && page.linkedMeeting) {
    lines.push(
      intl.formatMessage(mnv.linkedTo, {
        meeting: page.linkedMeeting.label ?? day(intl, page.linkedMeeting.startsAt, timeZone),
      }),
    )
  }
  if (page.state === 'AWAITING_CHAIR') {
    const approving = page.approvingMeeting
    if (approving) {
      lines.push(
        intl.formatMessage(mnv.approvedAt, {
          meeting: approving.label ?? day(intl, approving.startsAt, timeZone),
          date: day(intl, approving.startsAt, timeZone),
        }),
      )
      if (page.confirmer) lines.push(intl.formatMessage(mnv.amend, { name: page.confirmer.name }))
    } else {
      if (page.answers) lines.push(intl.formatMessage(mnv.periodEnded, { ...page.answers }))
      if (page.confirmer) lines.push(intl.formatMessage(mnv.waitingFor, { name: page.confirmer.name }))
    }
  }
  if (!lines.length) return null
  return (
    <div className="flex flex-col gap-1">
      {lines.map((line) => (
        <p key={line}>{line}</p>
      ))}
      {page.state === 'CIRCULATED' && !page.viewerRecused ? (
        <div>
          <Button variant="outline" className={big} onClick={onDeadline}>
            {intl.formatMessage(mn.changeDeadline)}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** The reviewer's last answer: sent back with comments, or agreed (with suggested changes to accept). */
function LastReview({ page, timeZone }: { page: Minutes; timeZone?: string }) {
  const intl = useIntl()
  const r = page.lastReview
  if (!r) return null
  const date = day(intl, r.at, timeZone)
  const line =
    r.decision === 'RETURN'
      ? intl.formatMessage(mnv.returned, { name: r.by.name, date })
      : r.onBehalfBy
        ? intl.formatMessage(mnv.agreedBehalf, {
            name: r.by.name,
            date,
            by: r.onBehalfBy.name,
            how: intl.formatMessage(mn[r.how ?? 'OTHER']),
          })
        : intl.formatMessage(mnv.agreed, { name: r.by.name, date })
  return (
    <div className="flex flex-col gap-1 rounded-md border p-3">
      <p className="font-medium">{line}</p>
      {r.comment ? (
        <p className="whitespace-pre-line">{intl.formatMessage(mnv.reviewComment, { comment: r.comment })}</p>
      ) : null}
      {r.decision === 'AGREE' && page.agreed ? <p>{intl.formatMessage(mnv.agreedNext, { name: r.by.name })}</p> : null}
    </div>
  )
}

/** The structure banner (flow AD3), only when something is wrong: each item's problem, "Restore item n" when missing. */
function StructureBanner({
  page,
  items,
  onRestore,
}: {
  page: Minutes
  items: Map<string, MinutesItem>
  onRestore: (item: MinutesItem) => void
}) {
  const intl = useIntl()
  const titleId = useId()
  const problems = page.structure.problems
  if (page.structure.ok !== false || !problems.length) return null
  const canFix = page.mayGenerate
  return (
    <section
      aria-labelledby={titleId}
      className="flex flex-col gap-2 rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-status-warn-fg"
    >
      <h2 id={titleId} className="flex items-center gap-2 text-lg font-semibold">
        <TriangleAlert aria-hidden="true" className="size-5" />
        {intl.formatMessage(mn.structureTitle)}
      </h2>
      <ul className="flex flex-col gap-2">
        {problems.map((p) => (
          <li key={`${p.problem}-${p.itemId ?? p.tag ?? ''}`} className="flex flex-wrap items-center gap-3">
            <span>{problemWords(intl, p)}</span>
            {p.problem === 'MISSING' && p.itemId && canFix ? (
              <Button
                variant="outline"
                className={big}
                onClick={() => {
                  onRestore(items.get(p.itemId ?? '') ?? { itemId: p.itemId ?? '', number: p.number ?? '?' })
                }}
              >
                {intl.formatMessage(mnv.restore, { number: p.number ?? '?' })}
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      <p>{intl.formatMessage(mn.structureCopies)}</p>
      {problems.some((p) => p.problem !== 'MISSING') ? <p>{intl.formatMessage(mn.structureRemake)}</p> : null}
    </section>
  )
}

function problemWords(intl: ReturnType<typeof useIntl>, p: StructureProblemResponse): string {
  const number = p.number ?? '?'
  switch (p.problem) {
    case 'MISSING':
      return intl.formatMessage(mnv.structureItem, { number })
    case 'DUPLICATED':
      return intl.formatMessage(mnv.structureDuplicated, { number })
    case 'NESTED':
      return intl.formatMessage(mnv.structureNested, { number })
    case 'UNLOCKED':
      return intl.formatMessage(mnv.structureUnlocked, { number })
    default:
      return intl.formatMessage(mnv.structureUnknown, { tag: p.tag ?? '' })
  }
}

/** "Fill an item again" (flow AD3): each item's block from its decision and notes; or the whole draft again. */
function FillItems({
  items,
  onFill,
  onRemake,
}: {
  items: MinutesItem[]
  onFill: (item: MinutesItem) => void
  onRemake: () => void
}) {
  const intl = useIntl()
  const titleId = useId()
  return (
    <section aria-labelledby={titleId} className="flex flex-col gap-2">
      <h2 id={titleId} className="text-xl font-semibold">
        {intl.formatMessage(mn.itemsTitle)}
      </h2>
      <p className="text-muted-foreground">{intl.formatMessage(mn.itemsLead)}</p>
      <ul className="flex flex-col divide-y border-y">
        {items.map((item) => (
          <li key={item.itemId} className="flex flex-wrap items-center justify-between gap-2 py-2">
            <span>{itemName(intl, item)}</span>
            <Button
              variant="outline"
              className={big}
              aria-label={intl.formatMessage(mnv.fillAgainNamed, { number: item.number })}
              onClick={() => {
                onFill(item)
              }}
            >
              {intl.formatMessage(mn.fillAgain)}
            </Button>
          </li>
        ))}
      </ul>
      <div>
        <Button variant="outline" className={big} onClick={onRemake}>
          {intl.formatMessage(mn.remakeAll)}
        </Button>
      </div>
    </section>
  )
}
