import { useState } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { im, imv } from './inbox-messages'

/**
 * Reassign the selected tasks (flow I1): the new person and a required reason. The caller sends the bulk REASSIGN,
 * which asks for step-up; the dialog stays open if that is cancelled or fails.
 */
export function ReassignDialog({
  count,
  busy,
  onConfirm,
  onClose,
}: {
  count: number
  busy: boolean
  onConfirm: (to: Picked, reason: string) => Promise<boolean>
  onClose: () => void
}) {
  const intl = useIntl()
  const [picked, setPicked] = useState<Picked>()
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<{ person?: string; reason?: string }>({})

  const confirm = async () => {
    const next = {
      person: picked ? undefined : intl.formatMessage(im.reassignPersonRequired),
      reason: reason.trim() ? undefined : intl.formatMessage(im.reassignReasonRequired),
    }
    setErrors(next)
    if (!picked || next.reason) return
    if (await onConfirm(picked, reason.trim())) onClose()
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(im.reassignTitle)}
      description={intl.formatMessage(imv.reassignLead, { count })}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(im.cancel)}
          </Button>
          <Button disabled={busy} onClick={() => void confirm()}>
            {intl.formatMessage(im.reassignConfirm)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="team-reassign-picker" className="text-sm font-medium">
            {intl.formatMessage(im.reassignTo)}
          </label>
          <PeoplePicker
            id="team-reassign-picker"
            kind="user"
            value={picked}
            onChange={(next) => {
              setPicked(next)
              setErrors((e) => ({ ...e, person: undefined }))
            }}
            invalid={Boolean(errors.person)}
            describedBy={errors.person ? 'team-reassign-person-error' : undefined}
          />
          {errors.person ? (
            <p id="team-reassign-person-error" className="text-xs font-medium text-destructive">
              {errors.person}
            </p>
          ) : null}
        </div>
        <Field
          label={intl.formatMessage(im.reassignReason)}
          hint={intl.formatMessage(im.reassignReasonHint)}
          required
          error={errors.reason}
        >
          {(p) => (
            <Textarea
              {...p}
              rows={3}
              maxLength={4000}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
                setErrors((current) => ({ ...current, reason: undefined }))
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}
