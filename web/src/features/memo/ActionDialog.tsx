import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  approveTask,
  discardMemo,
  rejectTask,
  resubmitMemo,
  returnTask,
  submitMemo,
  withdrawMemo,
} from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { actionButtons, dialogLeads, dialogTitles, mm } from './memo-messages'

export type MemoActionKind = 'submit' | 'resubmit' | 'withdraw' | 'discard' | 'approve' | 'return' | 'reject'

const REMARK_MAX = 4000

/** Return and reject need a remark; the others take an optional one, except discard, which takes none. */
const remarkOf: Record<MemoActionKind, 'required' | 'optional' | 'none'> = {
  submit: 'optional',
  resubmit: 'optional',
  withdraw: 'optional',
  discard: 'none',
  approve: 'optional',
  return: 'required',
  reject: 'required',
}

/**
 * The dialog of one memo action (design E01 §14, ux-review §6). The `Idempotency-Key` is made when the dialog opens, so a
 * double submit replays the first answer and a second device gets a conflict. Approve goes through step-up when the
 * server asks for it, with the same key. On phones the dialog fills the screen.
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
  const [key] = useState(() => crypto.randomUUID())
  const [remark, setRemark] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const needs = remarkOf[kind]

  const call = async (): Promise<MemoView> => {
    const headers = { 'Idempotency-Key': key }
    const body = { remark: remark.trim() || undefined }
    const memoPath = { path: { id: memo.id }, headers, throwOnError: true as const }
    const taskPath = { path: { taskId: memo.myTask?.id ?? '' }, headers, throwOnError: true as const }
    switch (kind) {
      case 'submit':
        return (await submitMemo({ ...memoPath, body })).data
      case 'resubmit':
        return (await resubmitMemo({ ...memoPath, body })).data
      case 'withdraw':
        return (await withdrawMemo({ ...memoPath, body })).data
      case 'discard':
        return (await discardMemo(memoPath)).data
      case 'approve':
        return (await withStepUp(() => approveTask({ ...taskPath, body }))).data
      case 'return':
        return (await returnTask({ ...taskPath, body })).data
      case 'reject':
        return (await rejectTask({ ...taskPath, body })).data
    }
  }

  const confirm = async () => {
    if (needs === 'required' && !remark.trim()) {
      setError(intl.formatMessage(mm.remarkRequired))
      return
    }
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
      title={intl.formatMessage(dialogTitles[kind], { reference: memo.reference })}
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
      {needs === 'none' ? null : (
        <Field
          label={intl.formatMessage(needs === 'required' ? mm.remark : mm.remarkOptional)}
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
    </Dialog>
  )
}
