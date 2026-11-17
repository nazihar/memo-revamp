import { useState } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { im } from './messages'

/** A confirmation that asks for a reason (closing needs one, abandoning may have one). */
export function ReasonDialog({
  title,
  text,
  confirm,
  required,
  onClose,
  onConfirm,
}: {
  title: string
  text: string
  confirm: string
  required: boolean
  onClose: () => void
  onConfirm: (reason: string) => Promise<void>
}) {
  const intl = useIntl()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    if (required && !reason.trim()) {
      setError(intl.formatMessage(im.required))
      return
    }
    setBusy(true)
    try {
      await onConfirm(reason.trim())
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
      description={text}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(im.cancel)}
          </Button>
          <Button variant="destructive" disabled={busy} onClick={() => void submit()}>
            {confirm}
          </Button>
        </>
      }
    >
      <Field
        label={intl.formatMessage(im.reason)}
        required={required}
        optionalLabel={intl.formatMessage(im.optional)}
        error={error}
      >
        {(p) => (
          <Textarea
            {...p}
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </Dialog>
  )
}
