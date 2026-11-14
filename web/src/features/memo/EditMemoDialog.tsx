import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { getMemoTypeFormOptions, listMemoTypesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { updateMemo } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import {
  EMPTY_METADATA,
  fieldErrors,
  metadataBody,
  metadataStateOf,
  type FormErrors,
  type MemoFieldValues,
  withValue,
} from './memo-fields'
import { MemoFields } from './MemoFields'
import { mm } from './memo-messages'
import { MetadataFields, type MetadataState } from './MetadataFields'

/**
 * Edits subject, type, classification and, while a draft, the type's details, behind `If-Match`. The classification and
 * the details are fixed once the memo was submitted; another type starts its details afresh.
 */
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
  const [metadata, setMetadata] = useState<MetadataState>(() => metadataStateOf(memo.details))
  const [errors, setErrors] = useState<FormErrors>({})
  const draft = memo.status === 'DRAFT'
  const types = useQuery(listMemoTypesOptions())
  const type = types.data?.find((t) => t.id === values.typeId)
  const form = useQuery({
    ...getMemoTypeFormOptions({ path: { id: values.typeId } }),
    enabled: draft && type?.hasFields === true,
  })
  const fields = draft && type?.hasFields ? (form.data?.fields ?? []) : []

  const change = (next: MemoFieldValues) => {
    if (next.typeId !== values.typeId) setMetadata(EMPTY_METADATA)
    setValues(next)
  }
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
          ...(draft ? { metadata: metadataBody(metadata) } : {}),
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
      <div className="flex flex-col gap-4">
        <MemoFields values={values} onChange={change} errors={errors} classificationLocked={!draft} />
        {fields.length > 0 ? (
          <MetadataFields
            fields={fields}
            state={metadata}
            errors={errors}
            onChange={(field, value, label) => {
              setMetadata((s) => withValue(s, field, value, label))
            }}
          />
        ) : null}
      </div>
    </Dialog>
  )
}
