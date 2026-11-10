import { useState } from 'react'
import { useIntl } from 'react-intl'
import { updateMemo } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { fieldErrors, type MemoFieldValues } from './memo-fields'
import { MemoFields } from './MemoFields'
import { mm } from './memo-messages'

/** Edits subject, type and classification behind `If-Match`; the classification is fixed once the memo was submitted. */
export function EditMemoDialog({
  memo,
  onClose,
  onSaved,
  onProblem,
}: {
  memo: MemoView
  onClose: () => void
  onSaved: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const [values, setValues] = useState<MemoFieldValues>({
    subject: memo.subject,
    typeId: memo.type.id,
    classificationId: memo.classification.id,
  })
  const [errors, setErrors] = useState<Partial<Record<keyof MemoFieldValues, string>>>({})
  const [busy, setBusy] = useState(false)

  const save = async () => {
    if (!values.subject.trim()) {
      setErrors({ subject: intl.formatMessage(mm.required) })
      return
    }
    setBusy(true)
    try {
      const saved = await updateMemo({
        path: { id: memo.id },
        headers: { 'If-Match': `"${memo.version}"` },
        body: {
          subject: values.subject.trim(),
          typeId: values.typeId,
          ...(values.classificationId ? { classificationId: values.classificationId } : {}),
        },
        throwOnError: true,
      })
      onSaved(saved.data)
    } catch (problem) {
      if (isProblem(problem) && problem.errors?.length) setErrors(fieldErrors(problem.errors))
      else onProblem(isProblem(problem) ? problem : undefined)
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
      title={intl.formatMessage(mm.actionEdit)}
      className="max-md:h-dvh max-md:max-h-none max-md:w-screen max-md:max-w-none max-md:rounded-none"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(mm.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void save()}>
            {intl.formatMessage(mm.save)}
          </Button>
        </>
      }
    >
      <MemoFields values={values} onChange={setValues} errors={errors} classificationLocked={memo.status !== 'DRAFT'} />
    </Dialog>
  )
}
