import { useMutation, useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { MoreHorizontal, ShieldCheck } from 'lucide-react'
import { lazy, Suspense, useState } from 'react'
import { defineMessages, useIntl, type IntlShape } from 'react-intl'
import { checkMemoApprovals } from '@/api/generated/sdk.gen'
import {
  getMemoHistoryOptions,
  listMemoApprovalsOptions,
  listMemoNotesOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { HistoryAction, NoteEntryView, StampView } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { pdm, pdmv } from '@/features/forums/papers-decision-messages'
import { pmv } from '@/features/forums/papers-messages'
import { cn } from '@/lib/utils'
import { actionLabels, mm, mmv } from '../memo-messages'
import { NoteRender } from './NoteRender'
import { roundsOf, type Item } from './note-rounds'
import type { NoteDoc } from './note-doc'

const WriteNoteDialog = lazy(() => import('./WriteNoteDialog'))

const m = defineMessages({
  title: { id: 'memo.notes.title', defaultMessage: 'Note sheet' },
  write: { id: 'memo.notes.write', defaultMessage: 'Write a note' },
  empty: { id: 'memo.notes.empty', defaultMessage: 'Nothing has been written on this memo yet.' },
  comment: { id: 'memo.notes.kind.comment', defaultMessage: 'Comment' },
  correction: { id: 'memo.notes.kind.correction', defaultMessage: 'Correction' },
  corrected: { id: 'memo.notes.corrected', defaultMessage: 'Corrected ↓' },
  correctionOf: { id: 'memo.notes.correctionOf', defaultMessage: 'Correction of an earlier note' },
  correct: { id: 'memo.notes.correct', defaultMessage: 'Correct' },
  more: { id: 'memo.notes.more', defaultMessage: 'More actions for this note' },
  check: { id: 'memo.notes.stamp.check', defaultMessage: 'Check' },
  checkTitle: { id: 'memo.notes.check.title', defaultMessage: 'Check the authenticated approvals' },
  checkOk: {
    id: 'memo.notes.check.ok',
    defaultMessage:
      'Checked just now: the document and attachments match what was approved, and the record has not changed.',
  },
  checkProblems: { id: 'memo.notes.check.problems', defaultMessage: 'Something does not match:' },
  close: { id: 'memo.notes.close', defaultMessage: 'Close' },
})

const mv = defineMessages<{
  current: { round: number }
  forName: { name: string }
  authenticated: { second: string; minutes: number }
  document: { number: number; hash: string }
  client: { ip: string; agent: string }
  stampLabel: { name: string; decision: string }
}>({
  current: { id: 'memo.notes.current', defaultMessage: 'Round {round} (current)' },
  forName: { id: 'memo.notes.for', defaultMessage: 'for {name}' },
  authenticated: {
    id: 'memo.notes.stamp.authenticated',
    defaultMessage:
      '{second, select, yes {Authenticated with password and authenticator, {minutes, plural, =0 {verified less than a minute before} one {verified # minute before} other {verified # minutes before}}} other {Authenticated with password}}',
  },
  document: { id: 'memo.notes.stamp.document', defaultMessage: 'Document v{number} · {hash}' },
  client: { id: 'memo.notes.stamp.client', defaultMessage: 'From {ip} · {agent}' },
  stampLabel: { id: 'memo.notes.stamp.label', defaultMessage: '{name} · {decision}' },
})

/**
 * The note sheet (ADR-0049, flow C): every round, newest first, with its remarks, comments and corrections, decisions as
 * stamps (ADR-0051) with their check, and the workflow's other events as small grey lines. It replaces the history.
 */
export function NoteSheet({ memoId, onChanged }: { memoId: string; onChanged: () => void }) {
  const intl = useIntl()
  const notes = useQuery(listMemoNotesOptions({ path: { id: memoId } }))
  const stamps = useQuery(listMemoApprovalsOptions({ path: { id: memoId } }))
  const history = useQuery(getMemoHistoryOptions({ path: { id: memoId } }))
  const [writing, setWriting] = useState<{ corrects?: NoteEntryView } | undefined>()
  const [checking, setChecking] = useState(false)

  if (notes.isPending || stamps.isPending || history.isPending) return <LoadingState rows={3} />
  if (notes.isError || stamps.isError || history.isError) {
    return <ErrorState onRetry={() => void Promise.all([notes.refetch(), stamps.refetch(), history.refetch()])} />
  }
  const rounds = roundsOf(
    notes.data.entries,
    stamps.data,
    history.data.flatMap((r) => r.actions.map((a) => ({ ...a, round: r.round }))),
  )
  // A memo not yet submitted has no workflow round: its notes are in the newest round, which is current.
  const currentRound = history.data.find((r) => r.current)?.round ?? rounds[0]?.[0]

  return (
    <div className="flex flex-col gap-4">
      {notes.data.canWrite ? (
        <div>
          <Button
            variant="outline"
            onClick={() => {
              setWriting({})
            }}
          >
            {intl.formatMessage(m.write)}
          </Button>
        </div>
      ) : null}
      {rounds.length === 0 ? <p className="text-sm text-muted-foreground">{intl.formatMessage(m.empty)}</p> : null}
      {rounds.map(([round, items]) => (
        <section key={round} aria-label={intl.formatMessage(mmv.round, { round })}>
          <h3 className="mb-2 text-sm font-semibold">
            {round === currentRound
              ? intl.formatMessage(mv.current, { round })
              : intl.formatMessage(mmv.round, { round })}
          </h3>
          {round === currentRound ? null : (
            <p className="mb-2 text-xs text-muted-foreground">{intl.formatMessage(mm.historyEarlier)}</p>
          )}
          <ol
            className={cn(
              'flex flex-col gap-3 border-s-2 ps-4',
              round === currentRound ? 'border-primary' : 'border-muted',
            )}
          >
            {items.map((item) => (
              <li key={keyOf(item)}>
                {item.kind === 'note' ? (
                  <NoteItem
                    note={item.note}
                    onCorrect={() => {
                      setWriting({ corrects: item.note })
                    }}
                  />
                ) : item.kind === 'stamp' ? (
                  <Stamp
                    stamp={item.stamp}
                    remark={item.remark}
                    plainRemark={item.plainRemark}
                    onCheck={() => {
                      setChecking(true)
                    }}
                  />
                ) : (
                  <EventLine action={item.action} />
                )}
              </li>
            ))}
          </ol>
        </section>
      ))}
      {writing ? (
        <Suspense fallback={null}>
          <WriteNoteDialog
            memoId={memoId}
            corrects={writing.corrects}
            onClose={() => {
              setWriting(undefined)
            }}
            onPosted={() => {
              setWriting(undefined)
              void notes.refetch()
              onChanged()
            }}
          />
        </Suspense>
      ) : null}
      {checking ? (
        <CheckDialog
          memoId={memoId}
          onClose={() => {
            setChecking(false)
          }}
        />
      ) : null}
    </div>
  )
}

function keyOf(item: Item): string {
  if (item.kind === 'note') return `n-${item.note.id}`
  if (item.kind === 'stamp') return `s-${item.stamp.id}`
  return `e-${item.action.seq}`
}

function Byline({ name, actingFor, label, at }: { name: string; actingFor?: string; label: string; at: string }) {
  const intl = useIntl()
  return (
    <span className="flex flex-wrap gap-x-2 text-sm">
      <span className="font-medium">{name}</span>
      {actingFor ? (
        <span className="text-muted-foreground">{intl.formatMessage(mv.forName, { name: actingFor })}</span>
      ) : null}
      <span>{label}</span>
      <time dateTime={at} className="text-muted-foreground">
        {intl.formatDate(at, { dateStyle: 'medium', timeStyle: 'short' })}
      </time>
    </span>
  )
}

function noteLabel(intl: IntlShape, note: NoteEntryView): string {
  // A committee's corrected wording, conditions or validity (E06 T4, flow AH1): a system note, not a comment.
  if (note.actionKind === 'FORUM_DECISION_CORRECTED') return intl.formatMessage(pdm.corrected)
  if (note.kind === 'COMMENT') return intl.formatMessage(m.comment)
  if (note.kind === 'CORRECTION') return intl.formatMessage(m.correction)
  // A committee's return, decline or decision (ADR-0058): "Returned by the Management Credit Committee".
  if (note.committee) {
    return intl.formatMessage(pmv.committeeNote, {
      outcome: note.committeeOutcome ?? 'other',
      committee: note.committee,
    })
  }
  const kind = note.actionKind as keyof typeof actionLabels | undefined
  return kind && kind in actionLabels ? intl.formatMessage(actionLabels[kind]) : intl.formatMessage(m.comment)
}

function NoteItem({ note, onCorrect }: { note: NoteEntryView; onCorrect: () => void }) {
  const intl = useIntl()
  const [menu, setMenu] = useState(false)
  const corrected = note.correctedBy.length > 0
  return (
    <article className={cn('flex flex-col gap-1', corrected && 'text-muted-foreground')} id={`note-${note.id}`}>
      <div className="flex items-start justify-between gap-2">
        <Byline
          name={note.author.name}
          actingFor={note.actingFor?.name}
          label={noteLabel(intl, note)}
          at={note.createdAt}
        />
        {note.canCorrect ? (
          <div className="relative">
            <button
              type="button"
              aria-label={intl.formatMessage(m.more)}
              aria-expanded={menu}
              onClick={() => {
                setMenu(!menu)
              }}
              className="rounded p-1 hover:bg-accent"
            >
              <MoreHorizontal className="size-4" aria-hidden="true" />
            </button>
            {menu ? (
              <button
                type="button"
                className="absolute end-0 z-10 mt-1 rounded-md border bg-popover px-3 py-1.5 text-sm shadow"
                onClick={() => {
                  setMenu(false)
                  onCorrect()
                }}
              >
                {intl.formatMessage(m.correct)}
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className={cn('flex flex-col gap-1', corrected && 'line-through decoration-1')}>
        {note.kind === 'CORRECTION' ? (
          <span className="text-xs text-muted-foreground">{intl.formatMessage(m.correctionOf)}</span>
        ) : null}
        <NoteRender doc={note.body as unknown as NoteDoc} readableMemos={note.readableMemos} />
        {note.resolution ? <ResolutionLine note={note} /> : null}
        {note.attachments.length > 0 ? (
          <ul className="flex flex-wrap gap-2 text-xs">
            {note.attachments.map((a) => (
              <li key={a.fileId}>
                <a className="underline" href={`/api/v1/files/${a.fileId}/content`}>
                  {a.name}
                </a>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
      {corrected ? (
        <a href={`#note-${note.correctedBy[note.correctedBy.length - 1] ?? ''}`} className="text-xs no-underline">
          {intl.formatMessage(m.corrected)}
        </a>
      ) : null}
    </article>
  )
}

function Stamp({
  stamp,
  remark,
  plainRemark,
  onCheck,
}: {
  stamp: StampView
  remark?: NoteEntryView
  plainRemark?: string
  onCheck: () => void
}) {
  const intl = useIntl()
  const label = intl.formatMessage(actionLabels[stamp.decision as keyof typeof actionLabels])
  const minutes = stamp.secondFactorAgeSeconds != null ? Math.floor(stamp.secondFactorAgeSeconds / 60) : 0
  return (
    <article
      className="flex flex-col gap-1 rounded-md border p-3"
      aria-label={intl.formatMessage(mv.stampLabel, { name: stamp.person.name, decision: label })}
    >
      <Byline name={stamp.person.name} actingFor={stamp.actingFor?.name} label={label} at={stamp.decidedAt} />
      {remark ? <NoteRender doc={remark.body as unknown as NoteDoc} readableMemos={remark.readableMemos} /> : null}
      {plainRemark ? <p className="whitespace-pre-wrap text-sm">{plainRemark}</p> : null}
      <span className="flex items-center gap-1 text-xs text-muted-foreground">
        <ShieldCheck className="size-3.5" aria-hidden="true" />
        {intl.formatMessage(mv.authenticated, { second: stamp.factors.includes('TOTP') ? 'yes' : 'no', minutes })}
      </span>
      <span className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
        {stamp.documentNumber != null && stamp.documentHash ? (
          <span className="font-mono">
            {intl.formatMessage(mv.document, { number: stamp.documentNumber, hash: stamp.documentHash })}
          </span>
        ) : (
          <span />
        )}
        <Button variant="outline" onClick={onCheck}>
          {intl.formatMessage(m.check)}
        </Button>
      </span>
      {stamp.client ? (
        <span className="text-xs text-muted-foreground">
          {intl.formatMessage(mv.client, { ip: stamp.client.ip ?? '–', agent: stamp.client.userAgent ?? '–' })}
        </span>
      ) : null}
    </article>
  )
}

function EventLine({ action }: { action: HistoryAction }) {
  const intl = useIntl()
  return (
    <span className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
      <span>{action.actor.name}</span>
      <span>{intl.formatMessage(actionLabels[action.kind])}</span>
      <time dateTime={action.occurredAt}>
        {intl.formatDate(action.occurredAt, { dateStyle: 'medium', timeStyle: 'short' })}
      </time>
      {action.remark ? <span className="whitespace-pre-wrap">{action.remark}</span> : null}
    </span>
  )
}

function CheckDialog({ memoId, onClose }: { memoId: string; onClose: () => void }) {
  const intl = useIntl()
  const check = useMutation({
    mutationFn: async () => (await checkMemoApprovals({ path: { id: memoId }, throwOnError: true })).data,
  })
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(m.checkTitle)}
      footer={
        <Button variant="outline" onClick={onClose}>
          {intl.formatMessage(m.close)}
        </Button>
      }
    >
      <div className="flex flex-col gap-3 text-sm" aria-live="polite">
        {check.data === undefined ? (
          <Button
            onClick={() => {
              check.mutate()
            }}
            disabled={check.isPending}
          >
            {intl.formatMessage(m.check)}
          </Button>
        ) : check.data.ok ? (
          <p>{intl.formatMessage(m.checkOk)}</p>
        ) : (
          <div>
            <p>{intl.formatMessage(m.checkProblems)}</p>
            <ul className="list-disc ps-5">
              {check.data.problems.map((p) => (
                <li key={`${p.approvalId}-${p.code}`}>{p.detail}</li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Dialog>
  )
}

/** "Resolution MCC/2026/001 · MCC 4/2026" with "Open the resolution" (flow AH1), under a committee's decision. */
function ResolutionLine({ note }: { note: NoteEntryView }) {
  const intl = useIntl()
  const number = note.resolution ?? ''
  return (
    <p className="flex flex-wrap items-center gap-x-3 text-sm">
      <span className="font-medium">
        {note.meetingLabel
          ? intl.formatMessage(pdmv.resolution, { number, meeting: note.meetingLabel })
          : intl.formatMessage(pdmv.resolutionOnly, { number })}
      </span>
      {note.resolutionId ? (
        <Link
          to="/resolutions/$resolutionId"
          params={{ resolutionId: note.resolutionId }}
          className="inline-flex min-h-11 items-center underline underline-offset-4"
        >
          {intl.formatMessage(pdm.openResolution)}
        </Link>
      ) : null}
    </p>
  )
}
