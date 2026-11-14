import { X } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  answerTask,
  approveTask,
  askOnTask,
  closeMemo,
  delegateTask,
  discardMemo,
  forwardTask,
  giveOpinion,
  recallMemo,
  recuseFromTask,
  referTask,
  rejectTask,
  resubmitMemo,
  returnTask,
  sendBackTask,
  submitMemo,
  withdrawMemo,
} from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Select, Textarea } from '@/components/ui/form-controls'
import { referenceOf } from './memo-fields'
import { actionButtons, dialogLeads, dialogTitles, mm, mmv } from './memo-messages'
import { newIdempotencyKey } from '@/lib/idempotency'

export type MemoActionKind =
  | 'submit'
  | 'resubmit'
  | 'withdraw'
  | 'discard'
  | 'approve'
  | 'recommend'
  | 'return'
  | 'reject'
  | 'recall'
  | 'sendBack'
  | 'forward'
  | 'delegate'
  | 'ask'
  | 'refer'
  | 'answer'
  | 'opinion'
  | 'recuse'
  | 'close'

const REMARK_MAX = 4000
const REFER_MAX = 10

/**
 * The remark each action takes (design E03 §2: return, reject, send back, forward, ask and refer always need one; an
 * answer and an opinion are the remark); discard takes none.
 */
const remarkOf: Record<MemoActionKind, 'required' | 'optional' | 'none'> = {
  submit: 'optional',
  resubmit: 'optional',
  withdraw: 'optional',
  discard: 'none',
  approve: 'optional',
  recommend: 'optional',
  return: 'required',
  reject: 'required',
  recall: 'optional',
  sendBack: 'required',
  forward: 'required',
  delegate: 'optional',
  ask: 'required',
  refer: 'required',
  answer: 'required',
  opinion: 'required',
  recuse: 'required',
  close: 'optional',
}

/** Who the action needs besides the remark: one person, several, or an earlier step to send the memo back to. */
const targetOf: Partial<Record<MemoActionKind, 'person' | 'people' | 'step'>> = {
  forward: 'person',
  delegate: 'person',
  ask: 'person',
  refer: 'people',
  sendBack: 'step',
}

/**
 * The dialog of one memo action (design E01 §14, E03 §12, ux-review §6). The `Idempotency-Key` is made when the dialog
 * opens, so a double submit replays the first answer and a second device gets a conflict. Approve goes through step-up
 * when the server asks for it, with the same key. On phones the dialog fills the screen.
 */
export function ActionDialog({
  memo,
  kind,
  onClose,
  onDone,
  onProblem,
}: {
  memo: MemoView
  kind: MemoActionKind
  onClose: () => void
  onDone: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const withStepUp = useWithStepUp()
  const [key] = useState(() => newIdempotencyKey())
  const [remark, setRemark] = useState('')
  const [picked, setPicked] = useState<Picked>()
  const [people, setPeople] = useState<Picked[]>([])
  const [step, setStep] = useState(memo.sendBackTargets[0]?.stepId ?? '')
  const [error, setError] = useState<string>()
  const [targetError, setTargetError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const needs = remarkOf[kind]
  const target = targetOf[kind]

  const call = async (): Promise<MemoView> => {
    const headers = { 'Idempotency-Key': key }
    const text = remark.trim() || undefined
    const body = { remark: text }
    const memoPath = { path: { id: memo.id }, headers, throwOnError: true as const }
    const taskPath = { path: { taskId: memo.myTask?.id ?? '' }, headers, throwOnError: true as const }
    const to = picked?.id ?? ''
    switch (kind) {
      case 'submit':
        return (await submitMemo({ ...memoPath, body })).data
      case 'resubmit':
        return (await resubmitMemo({ ...memoPath, body })).data
      case 'withdraw':
        return (await withdrawMemo({ ...memoPath, body })).data
      case 'discard':
        return (await discardMemo(memoPath)).data
      case 'recall':
        return (await recallMemo({ ...memoPath, body })).data
      case 'approve':
      case 'recommend':
        // A review step's positive action is Recommend; the server records which one it was.
        return (await withStepUp(() => approveTask({ ...taskPath, body }))).data
      case 'return':
        return (await returnTask({ ...taskPath, body })).data
      case 'reject':
        return (await rejectTask({ ...taskPath, body })).data
      case 'sendBack':
        return (await sendBackTask({ ...taskPath, body: { targetStepId: step, remark: text } })).data
      case 'forward':
        return (await forwardTask({ ...taskPath, body: { to, remark: text } })).data
      case 'delegate':
        return (await delegateTask({ ...taskPath, body: { to, remark: text } })).data
      case 'ask':
        return (await askOnTask({ ...taskPath, body: { to, remark: text } })).data
      case 'refer':
        return (await referTask({ ...taskPath, body: { to: people.map((p) => p.id), remark: text } })).data
      case 'answer':
        return (await answerTask({ ...taskPath, body })).data
      case 'opinion':
        return (await giveOpinion({ ...taskPath, body })).data
      case 'recuse':
        return (await recuseFromTask({ ...taskPath, body })).data
      case 'close':
        return (await closeMemo({ ...memoPath, body })).data
    }
  }

  const missingTarget = (): string | undefined => {
    if (target === 'person' && !picked) return intl.formatMessage(mm.personRequired)
    if (target === 'people' && people.length === 0) return intl.formatMessage(mm.peopleRequired)
    if (target === 'step' && !step) return intl.formatMessage(mm.stepRequired)
    return undefined
  }

  const confirm = async () => {
    const noTarget = missingTarget()
    const noRemark = needs === 'required' && !remark.trim()
    setTargetError(noTarget)
    setError(noRemark ? intl.formatMessage(mm.remarkRequired) : undefined)
    if (noTarget || noRemark) return
    setBusy(true)
    try {
      onDone(await call())
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
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
      title={intl.formatMessage(dialogTitles[kind], { reference: referenceOf(intl, memo.reference) })}
      description={intl.formatMessage(dialogLeads[kind], { number: memo.document?.number ?? 0 })}
      className="max-md:h-dvh max-md:max-h-none max-md:w-screen max-md:max-w-none max-md:rounded-none"
      footer={
        <>
          <Button variant="outline" className="min-h-11" onClick={onClose}>
            {intl.formatMessage(mm.cancel)}
          </Button>
          <Button
            className="min-h-11"
            variant={kind === 'reject' || kind === 'discard' ? 'destructive' : 'default'}
            disabled={busy}
            onClick={() => void confirm()}
          >
            {intl.formatMessage(actionButtons[kind])}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {target === 'step' ? (
          <Field label={intl.formatMessage(mm.sendBackTo)} required error={targetError}>
            {(p) => (
              <Select
                {...p}
                value={step}
                onChange={(e) => {
                  setStep(e.target.value)
                  setTargetError(undefined)
                }}
              >
                {memo.sendBackTargets.map((t) => (
                  <option key={t.stepId} value={t.stepId}>
                    {intl.formatMessage(mmv.sendBackTarget, { step: t.name ?? '', name: t.holder.name })}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : null}
        {target === 'person' || target === 'people' ? (
          <Field label={intl.formatMessage(target === 'people' ? mm.referTo : mm.handTo)} required error={targetError}>
            {(p) => (
              <PeoplePicker
                id={p.id}
                kind="user"
                value={picked}
                invalid={Boolean(targetError)}
                {...(p['aria-describedby'] ? { describedBy: p['aria-describedby'] } : {})}
                onChange={(next) => {
                  setTargetError(undefined)
                  if (target === 'person') {
                    setPicked(next)
                  } else if (next && !people.some((x) => x.id === next.id) && people.length < REFER_MAX) {
                    setPeople([...people, next])
                  }
                }}
              />
            )}
          </Field>
        ) : null}
        {target === 'people' && people.length > 0 ? (
          <ul className="flex flex-wrap gap-2">
            {people.map((person) => (
              <li key={person.id} className="flex items-center gap-1 rounded-md border px-2 py-1 text-sm">
                {person.label}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mmv.routeRemove, { name: person.label })}
                  onClick={() => {
                    setPeople(people.filter((x) => x.id !== person.id))
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        ) : null}
        {needs === 'none' ? null : (
          <Field
            label={intl.formatMessage(needs === 'required' ? remarkLabel(kind) : mm.remarkOptional)}
            required={needs === 'required'}
            error={error}
          >
            {(p) => (
              <Textarea
                {...p}
                rows={4}
                maxLength={REMARK_MAX}
                value={remark}
                onChange={(e) => {
                  setRemark(e.target.value)
                  setError(undefined)
                }}
              />
            )}
          </Field>
        )}
      </div>
    </Dialog>
  )
}

/** The remark field's label: an answer and an opinion are what the person writes. */
function remarkLabel(kind: MemoActionKind) {
  if (kind === 'answer') return mm.answerText
  if (kind === 'opinion') return mm.opinionText
  return mm.remark
}
