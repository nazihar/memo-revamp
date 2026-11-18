import { useState, type ReactNode, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'
import { fm } from './messages'

/**
 * A form in a dialog, or in a side sheet on the inline-end side (full screen on a phone): one primary button, Cancel,
 * and a refusal shown in words inside the form (role="alert") rather than in a passing toast, so nobody misses it.
 * [onSubmit] returns a refusal to show, or nothing when it worked.
 */
export function FormDialog({
  title,
  description,
  submitLabel,
  side = false,
  destructive = false,
  onSubmit,
  onClose,
  children,
}: {
  title: string
  description?: ReactNode
  submitLabel: string
  side?: boolean
  destructive?: boolean
  onSubmit: () => Promise<string | undefined>
  onClose: () => void
  children?: ReactNode
}) {
  const intl = useIntl()
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<string>()

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setRefusal(undefined)
    try {
      setRefusal(await onSubmit())
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
      description={description}
      className={cn(
        side &&
          'fixed inset-y-0 end-0 start-auto m-0 h-dvh max-h-none w-full max-w-full overflow-y-auto rounded-none border-y-0 border-e-0 sm:w-[34rem]',
      )}
    >
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-5">
        {children}
        {refusal ? (
          <p
            role="alert"
            className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
          >
            {refusal}
          </p>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
          <Button type="button" variant="outline" onClick={onClose}>
            {intl.formatMessage(fm.cancel)}
          </Button>
          <Button type="submit" variant={destructive ? 'destructive' : 'default'} disabled={busy}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
