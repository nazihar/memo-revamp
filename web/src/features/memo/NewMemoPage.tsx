import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { getMemoTypeFormOptions, listMemoTypesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { createMemo } from '@/api/generated/sdk.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { newIdempotencyKey } from '@/lib/idempotency'
import {
  EMPTY_METADATA,
  fieldErrors,
  metadataBody,
  type FormErrors,
  type MemoFieldValues,
  withValue,
} from './memo-fields'
import { mm } from './memo-messages'
import { MemoFields } from './MemoFields'
import { MemoTypeChooser } from './MemoTypeChooser'
import { MetadataFields, type MetadataState } from './MetadataFields'

const m = defineMessages<{ details: { type: string }; detailsLead: Record<string, never> }>({
  details: { id: 'memo.create.details', defaultMessage: '{type} details' },
  detailsLead: {
    id: 'memo.create.details.lead',
    defaultMessage: 'You can save the draft with gaps; everything marked * is needed before you submit.',
  },
})

/**
 * `/memos/new` (ux-review B1–B2): the type first, then subject, classification and the type's own fields (ADR-0043). A
 * draft saves with required fields still empty; submit checks them. The draft opens next for the document and route.
 */
export function NewMemoPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const toast = useToast()
  const types = useQuery(listMemoTypesOptions())
  const [values, setValues] = useState<MemoFieldValues>({ subject: '', typeId: '', classificationId: '' })
  const [metadata, setMetadata] = useState<MetadataState>(EMPTY_METADATA)
  const [errors, setErrors] = useState<FormErrors>({})
  const [busy, setBusy] = useState(false)
  // One key per form: a double click creates one draft.
  const [key] = useState(() => newIdempotencyKey())

  // Until the user picks one, the first active type.
  const typeId = values.typeId !== '' ? values.typeId : (types.data?.[0]?.id ?? '')
  const type = types.data?.find((t) => t.id === typeId)
  const form = useQuery({ ...getMemoTypeFormOptions({ path: { id: typeId } }), enabled: type?.hasFields === true })
  const fields = type?.hasFields ? (form.data?.fields ?? []) : []

  const chooseType = (id: string) => {
    if (id === typeId) return
    setValues({ ...values, typeId: id })
    // Another type asks for other details.
    setMetadata(EMPTY_METADATA)
  }

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!values.subject.trim()) {
      setErrors({ subject: intl.formatMessage(mm.required) })
      return
    }
    setBusy(true)
    try {
      const body = metadataBody(metadata)
      const created = await createMemo({
        headers: { 'Idempotency-Key': key },
        body: {
          subject: values.subject.trim(),
          typeId,
          ...(values.classificationId ? { classificationId: values.classificationId } : {}),
          ...(Object.keys(body).length > 0 ? { metadata: body } : {}),
        },
        throwOnError: true,
      })
      await navigate({ to: '/memos/$memoId', params: { memoId: created.data.id } })
    } catch (problem) {
      if (isProblem(problem) && problem.errors?.length) setErrors(fieldErrors(problem.errors))
      else toast.show(intl.formatMessage(mm.failed), 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="new-memo-title" className="max-w-2xl">
      <PageHeader
        title={<span id="new-memo-title">{intl.formatMessage(mm.createTitle)}</span>}
        description={intl.formatMessage(mm.createLead)}
      />
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-6">
        <MemoTypeChooser types={types.data ?? []} value={typeId} onChange={chooseType} error={errors.typeId} />
        <MemoFields values={{ ...values, typeId }} onChange={setValues} errors={errors} showType={false} />
        {fields.length > 0 && type ? (
          <fieldset className="flex flex-col gap-4 border-t pt-4">
            <legend className="text-base font-semibold">{intl.formatMessage(m.details, { type: type.name })}</legend>
            <p className="text-sm text-muted-foreground">{intl.formatMessage(m.detailsLead)}</p>
            <MetadataFields
              fields={fields}
              state={metadata}
              errors={errors}
              onChange={(field, value, label) => {
                setMetadata((s) => withValue(s, field, value, label))
              }}
            />
          </fieldset>
        ) : null}
        <div className="flex gap-2">
          <Button type="submit" disabled={busy || !typeId}>
            {intl.formatMessage(mm.create)}
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => void navigate({ to: '/inbox', search: { tab: 'drafts' } })}
          >
            {intl.formatMessage(mm.cancel)}
          </Button>
        </div>
      </form>
    </section>
  )
}
