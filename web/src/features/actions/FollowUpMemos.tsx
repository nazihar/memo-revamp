import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { FileText, Lock } from 'lucide-react'
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import {
  getActionEntriesQueryKey,
  getActionQueryKey,
  listClassificationLevelsOptions,
  listMemoTypesOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { linkActionMemo, raiseActionMemo, suggestSearch } from '@/api/generated/sdk.gen'
import type { ActionMemoResponse, ActionResponse } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useMyAccess } from '@/features/access/access'
import { statusLabels } from '@/features/memo/memo-messages'
import { MemoTypeChooser } from '@/features/memo/MemoTypeChooser'
import { statusTone } from '@/features/memo/memo-status'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { am } from './messages'
import { actionRefusal, fieldProblems, staleRefusal } from './problems'
import type { Done } from './ActionDialogs'
import { referenceWords } from './words'

type MemoStatus = keyof typeof statusLabels

const isMemoStatus = (s: string | null | undefined): s is MemoStatus => !!s && s in statusLabels

/** The memo's status in words, when the viewer is given it. */
function MemoStatusBadge({ status }: { status?: string | null }) {
  const intl = useIntl()
  if (!isMemoStatus(status)) return null
  return <Badge tone={statusTone[status]}>{intl.formatMessage(statusLabels[status])}</Badge>
}

/**
 * The action's follow-up memos (flow AP, design §1.11): those the viewer reads with their number, subject and status, each
 * opening the memo; the others as "A follow-up memo (you do not have access)" with the status only for the verifier and
 * the secretaries. An approved memo on an open action reminds the owner that it is theirs to mark as done.
 */
export function FollowUpMemos({ action }: { action: ActionResponse }) {
  const intl = useIntl()
  const headingId = useId()
  const approved = action.memos.some((m) => m.status === 'APPROVED')
  const open = action.status === 'OPEN' || action.status === 'IN_PROGRESS'
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-xl font-semibold">
        {intl.formatMessage(am.memos)}
      </h2>
      {action.memos.length === 0 ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.memosNone)}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {action.memos.map((m) => (
            <li key={m.memoId}>
              <MemoLine memo={m} />
            </li>
          ))}
        </ul>
      )}
      {approved && open && action.can.markDone ? (
        <p className="rounded-md bg-status-ok-bg px-3 py-2 text-status-ok-fg">
          {intl.formatMessage(am.memoApprovedNext)}
        </p>
      ) : null}
    </section>
  )
}

export function MemoLine({ memo: m }: { memo: ActionMemoResponse }) {
  const intl = useIntl()
  const kind = intl.formatMessage(m.kind === 'RAISED' ? am.memoRaisedKind : am.memoLinkedKind)
  if (!m.readable) {
    return (
      <div className="flex flex-wrap items-center gap-2 rounded-md border px-3 py-2">
        <Lock aria-hidden="true" className="size-4 shrink-0" />
        <span>{intl.formatMessage(am.memoHidden)}</span>
        <MemoStatusBadge status={m.status} />
      </div>
    )
  }
  const name = intl.formatMessage(am.memoLine, {
    reference: m.reference ?? intl.formatMessage(am.memoUnnumbered),
    subject: m.subject ?? '',
  })
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border px-3 py-2">
      <Link
        to="/memos/$memoId"
        params={{ memoId: m.memoId }}
        className="inline-flex min-h-11 items-center gap-2 font-medium underline-offset-4 hover:underline"
      >
        <FileText aria-hidden="true" className="size-4 shrink-0" />
        {name}
      </Link>
      <MemoStatusBadge status={m.status} />
      <span className="text-sm text-muted-foreground">{kind}</span>
    </div>
  )
}

/**
 * AP1, "Raise a memo from BRC A9/2026": the memo type (the types the person may create), the subject (the action's title
 * to start with) and the level (the action's, which may be changed; lower only with a confirmation). The draft memo opens
 * next, from the type's template.
 */
export function RaiseMemoDialog({ action, onClose }: { action: ActionResponse; onClose: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const terms = useTerms()
  const navigate = useNavigate()
  const types = useQuery(listMemoTypesOptions())
  const levels = useQuery(listClassificationLevelsOptions())
  const access = useMyAccess()
  const clearance = access.data?.clearance.rank ?? 0
  const [key] = useState(() => newIdempotencyKey())
  const [typeId, setTypeId] = useState('')
  const [subject, setSubject] = useState(action.title)
  const [changing, setChanging] = useState(false)
  const [level, setLevel] = useState(action.classificationId)
  const [confirmLower, setConfirmLower] = useState(false)
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const formId = useId()

  const chosenType = typeId !== '' ? typeId : (types.data?.[0]?.id ?? '')
  const actionLevel = levels.data?.find((l) => l.id === action.classificationId)
  const memoLevel = levels.data?.find((l) => l.id === level)
  const lower = !!actionLevel && !!memoLevel && memoLevel.rank < actionLevel.rank

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!chosenType) {
      setErrors({ typeId: intl.formatMessage(am.fieldTypeUnknown) })
      return
    }
    if (lower && !confirmLower) {
      setErrors({ classificationId: intl.formatMessage(am.fieldLowerLevel) })
      return
    }
    setBusy(true)
    setErrors({})
    setRefusal(undefined)
    try {
      const raised = await raiseActionMemo({
        path: { actionId: action.id },
        headers: { 'Idempotency-Key': key },
        body: {
          typeId: chosenType,
          ...(subject.trim() ? { subject: subject.trim() } : {}),
          ...(level !== action.classificationId ? { classificationId: level } : {}),
          ...(lower ? { confirmLowerLevel: true } : {}),
        },
        throwOnError: true,
      })
      // Back on the action from the memo ("Raised from action …"), its page lists the new memo at once (E07 T12).
      queryClient.setQueryData(getActionQueryKey({ path: { actionId: action.id } }), raised.data.action)
      void queryClient.invalidateQueries({ queryKey: getActionEntriesQueryKey({ path: { actionId: action.id } }) })
      await navigate({ to: '/memos/$memoId', params: { memoId: raised.data.memoId } })
    } catch (problem) {
      const fields = Object.fromEntries(
        Object.entries(fieldProblems(intl, terms, problem)).filter(([f]) =>
          ['typeId', 'subject', 'classificationId'].includes(f),
        ),
      )
      setErrors(fields)
      if (!Object.keys(fields).length) setRefusal(actionRefusal(intl, terms, problem))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(am.raiseTitle, { reference: referenceWords(intl, action.reference) })}
      description={intl.formatMessage(am.raiseLead)}
      footer={
        <>
          <Button type="button" variant="outline" className="min-h-11" onClick={onClose}>
            {intl.formatMessage(am.cancel)}
          </Button>
          <Button type="submit" form={formId} className="min-h-11" disabled={busy || !types.data?.length}>
            {intl.formatMessage(am.raiseSend)}
          </Button>
        </>
      }
    >
      <form id={formId} noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        {types.data?.length === 0 ? (
          <p role="alert">{intl.formatMessage(am.raiseNoTypes)}</p>
        ) : types.data ? (
          <MemoTypeChooser types={types.data} value={chosenType} onChange={setTypeId} error={errors.typeId} />
        ) : null}
        <Field label={intl.formatMessage(am.raiseSubject)} error={errors.subject}>
          {(p) => (
            <Input
              {...p}
              name="subject"
              maxLength={300}
              value={subject}
              className="text-base"
              onChange={(e) => {
                setSubject(e.target.value)
              }}
            />
          )}
        </Field>
        {changing ? (
          <Field label={intl.formatMessage(am.raiseLevel)} error={errors.classificationId}>
            {(p) => (
              <Select
                {...p}
                name="classificationId"
                value={level}
                onChange={(e) => {
                  setLevel(e.target.value)
                  setConfirmLower(false)
                }}
              >
                {(levels.data ?? [])
                  .filter((l) => l.rank <= clearance || l.id === action.classificationId)
                  .map((l) => (
                    <option key={l.id} value={l.id}>
                      {l.name}
                    </option>
                  ))}
              </Select>
            )}
          </Field>
        ) : (
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-medium">{intl.formatMessage(am.raiseLevel)}</span>
            <div className="flex flex-wrap items-center gap-3">
              <span>{intl.formatMessage(am.raiseLevelFromAction, { level: actionLevel?.name ?? '' })}</span>
              <Button
                type="button"
                variant="outline"
                className="min-h-11"
                onClick={() => {
                  setChanging(true)
                }}
              >
                {intl.formatMessage(am.raiseLevelChange)}
              </Button>
            </div>
          </div>
        )}
        {lower ? (
          <label className="flex min-h-11 items-center gap-3">
            <Checkbox
              name="confirmLowerLevel"
              checked={confirmLower}
              onChange={(e) => {
                setConfirmLower(e.target.checked)
              }}
            />
            <span>
              {intl.formatMessage(am.raiseLowerConfirm, {
                memo: memoLevel.name,
                action: actionLevel.name,
              })}
            </span>
          </label>
        ) : null}
        {refusal ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {refusal}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}

/** Waits [ms] after the last change of [value] before giving it. */
function useSettled(value: string, ms = 300): string {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => {
      setSettled(value)
    }, ms)
    return () => {
      clearTimeout(t)
    }
  }, [value, ms])
  return settled
}

/**
 * AP3, "Link an existing memo": a search over the memos the person can read (the search suggestions, which apply the
 * memo's read rule), each found memo with its own "Link" button.
 */
export function LinkMemoDialog({
  action,
  onClose,
  onDone,
  onStale,
}: {
  action: ActionResponse
  onClose: () => void
  onDone: Done
  onStale: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const [key] = useState(() => newIdempotencyKey())
  const [q, setQ] = useState('')
  const term = useSettled(q.trim())
  const [busy, setBusy] = useState<string>()
  const [refusal, setRefusal] = useState<string>()
  const refusalRef = useRef<HTMLParagraphElement>(null)
  const found = useQuery({
    queryKey: ['actions', 'link-suggest', term],
    queryFn: async ({ signal }) => (await suggestSearch({ body: { q: term }, signal, throwOnError: true })).data,
    enabled: term.length >= 2,
  })
  const linked = new Set(action.memos.map((m) => m.memoId))
  const results = (found.data ?? []).filter((r) => !linked.has(r.memoId))

  const link = async (memoId: string) => {
    setBusy(memoId)
    setRefusal(undefined)
    try {
      const next = await linkActionMemo({
        path: { actionId: action.id },
        headers: { 'Idempotency-Key': `${key}-${memoId}` },
        body: { memoId },
        throwOnError: true,
      })
      onDone(next.data, intl.formatMessage(am.linkChosen))
    } catch (problem) {
      const fields = fieldProblems(intl, terms, problem)
      setRefusal(fields.memoId ?? fields.memos ?? actionRefusal(intl, terms, problem))
      if (staleRefusal(problem)) onStale()
      refusalRef.current?.focus()
    } finally {
      setBusy(undefined)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(am.linkTitle)}
      footer={
        <Button type="button" variant="outline" className="min-h-11" onClick={onClose}>
          {intl.formatMessage(am.cancel)}
        </Button>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={intl.formatMessage(am.linkSearch)} hint={intl.formatMessage(am.linkSearchHint)}>
          {(p) => (
            <Input
              {...p}
              type="search"
              name="q"
              value={q}
              className="text-base"
              onChange={(e) => {
                setQ(e.target.value)
              }}
            />
          )}
        </Field>
        <div aria-live="polite">
          {term.length < 2 ? null : found.isPending ? (
            <p className="text-sm text-muted-foreground">{intl.formatMessage(am.linkSearching)}</p>
          ) : results.length === 0 ? (
            <p className="text-sm">{intl.formatMessage(am.linkNone)}</p>
          ) : (
            <ul aria-label={intl.formatMessage(am.linkResults)} className="flex flex-col gap-2">
              {results.map((r) => {
                const name = intl.formatMessage(am.memoLine, {
                  reference: r.number ?? intl.formatMessage(am.memoUnnumbered),
                  subject: r.subject,
                })
                return (
                  <li
                    key={r.memoId}
                    className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2"
                  >
                    <span className="min-w-0 flex-1">{name}</span>
                    <Button
                      type="button"
                      variant="outline"
                      className="min-h-11"
                      disabled={busy !== undefined}
                      aria-label={intl.formatMessage(am.linkChoose, { name })}
                      onClick={() => void link(r.memoId)}
                    >
                      {intl.formatMessage(am.linkButton)}
                    </Button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
        {refusal ? (
          <p ref={refusalRef} tabIndex={-1} role="alert" className="text-sm font-medium text-destructive">
            {refusal}
          </p>
        ) : null}
      </div>
    </Dialog>
  )
}
