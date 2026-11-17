import { useQueryClient } from '@tanstack/react-query'
import { useState, type SyntheticEvent } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import { listSavedSearchesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { createSavedSearch } from '@/api/generated/sdk.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { sm } from './search-messages'
import { criteriaOf, type SearchParams } from './search-params'
import { saveProblem } from './search-problems'

/** A dialog that asks for a saved search's name; refusals (the limit, a bad name) show at the field. */
export function NameDialog({
  title,
  initial = '',
  hint,
  submitLabel,
  onSave,
  onClose,
}: {
  title: string
  initial?: string
  hint?: string
  submitLabel: string
  onSave: (name: string) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const [name, setName] = useState(initial)
  const [error, setError] = useState<MessageDescriptor>()
  const [busy, setBusy] = useState(false)
  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (busy) return
    if (!name.trim()) {
      setError(sm.nameRequired)
      return
    }
    setBusy(true)
    try {
      await onSave(name.trim())
      onClose()
    } catch (problem) {
      setError(saveProblem(problem))
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
      title={title}
    >
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(sm.name)} required hint={hint} error={error && intl.formatMessage(error)}>
          {(p) => (
            <Input
              {...p}
              name="name"
              value={name}
              maxLength={200}
              autoComplete="off"
              onChange={(e) => {
                setName(e.target.value)
                setError(undefined)
              }}
            />
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(sm.cancel)}
          </Button>
          <Button type="submit" disabled={busy}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** "Save this search" (flow H3): the current words, filters and order, under a name only the person sees. */
export function SaveSearchDialog({ params, q, onClose }: { params: SearchParams; q: string; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  return (
    <NameDialog
      title={intl.formatMessage(sm.saveTitle)}
      hint={intl.formatMessage(sm.privacy)}
      submitLabel={intl.formatMessage(sm.save)}
      onSave={async (name) => {
        await createSavedSearch({ body: { name, criteria: criteriaOf(params, q) }, throwOnError: true })
        toast.show(intl.formatMessage(sm.saved))
        await queryClient.invalidateQueries({ queryKey: listSavedSearchesQueryKey() })
      }}
      onClose={onClose}
    />
  )
}
