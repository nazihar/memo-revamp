import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { useState, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { listMemoTypesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { createMemo } from '@/api/generated/sdk.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { fieldErrors, type MemoFieldValues } from './memo-fields'
import { MemoFields } from './MemoFields'
import { mm } from './memo-messages'

/** `/memos/new` (ux-review §3): subject, type and classification; the draft opens next for the document and route. */
export function NewMemoPage() {
  const intl = useIntl()
  const navigate = useNavigate()
  const toast = useToast()
  const types = useQuery(listMemoTypesOptions())
  const [values, setValues] = useState<MemoFieldValues>({ subject: '', typeId: '', classificationId: '' })
  const [errors, setErrors] = useState<Partial<Record<keyof MemoFieldValues, string>>>({})
  const [busy, setBusy] = useState(false)
  // One key per form: a double click creates one draft.
  const [key] = useState(() => crypto.randomUUID())

  // Until the user picks one, the first active type.
  const typeId = values.typeId !== '' ? values.typeId : (types.data?.[0]?.id ?? '')

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!values.subject.trim()) {
      setErrors({ subject: intl.formatMessage(mm.required) })
      return
    }
    setBusy(true)
    try {
      const created = await createMemo({
        headers: { 'Idempotency-Key': key },
        body: {
          subject: values.subject.trim(),
          typeId,
          ...(values.classificationId ? { classificationId: values.classificationId } : {}),
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
        <MemoFields values={{ ...values, typeId }} onChange={setValues} errors={errors} />
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
