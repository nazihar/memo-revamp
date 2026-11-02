import { useState, type ReactNode, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import type { OrgUnit } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { om, omv, orgProblemMessage, today } from './org-messages'
import { PeoplePicker, type Picked } from './PeoplePicker'

/**
 * A dialog with one form: [onSubmit] runs the change; a refusal is shown as a toast and keeps the dialog open, success
 * closes it. [valid] gates submission (the server checks again).
 */
export function FormDialog({
  title,
  description,
  submitLabel,
  valid,
  onSubmit,
  onClose,
  problemMessage,
  children,
}: {
  title: string
  description?: string
  submitLabel: string
  valid: boolean
  onSubmit: () => Promise<unknown>
  onClose: () => void
  /** How a refusal reads; org refusals by default. */
  problemMessage?: (error: unknown) => string
  children: ReactNode
}) {
  const intl = useIntl()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    try {
      await onSubmit()
      toast.show(intl.formatMessage(om.saved))
      onClose()
    } catch (error) {
      toast.show(problemMessage ? problemMessage(error) : orgProblemMessage(intl, error), 'error')
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
    >
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        {children}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(om.cancel)}
          </Button>
          <Button type="submit" disabled={!valid || busy}>
            {submitLabel}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Code and name of a new or renamed unit. */
export function UnitFormDialog({
  title,
  initial,
  withCode = true,
  onSave,
  onClose,
}: {
  title: string
  /** False for a legal entity, which has a name only. */
  withCode?: boolean
  initial?: { code: string; name: string }
  onSave: (value: { code: string; name: string }) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const [code, setCode] = useState(initial?.code ?? '')
  const [name, setName] = useState(initial?.name ?? '')
  return (
    <FormDialog
      title={title}
      submitLabel={intl.formatMessage(om.save)}
      valid={(!withCode || code.trim().length > 0) && name.trim().length > 0}
      onSubmit={() => onSave({ code: code.trim(), name: name.trim() })}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(om.name)} required>
        {(p) => (
          <Input
            {...p}
            value={name}
            autoComplete="off"
            onChange={(e) => {
              setName(e.target.value)
            }}
          />
        )}
      </Field>
      {withCode ? (
        <Field label={intl.formatMessage(om.code)} required>
          {(p) => (
            <Input
              {...p}
              value={code}
              autoComplete="off"
              maxLength={30}
              onChange={(e) => {
                setCode(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}

/** A new parent for [unit] among the units of its legal entity, outside its own subtree. */
export function MoveUnitDialog({
  unit,
  units,
  onSave,
  onClose,
}: {
  unit: OrgUnit
  units: OrgUnit[]
  onSave: (parentId: string) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const inSubtree = new Set<string>([unit.id])
  let grew = true
  while (grew) {
    grew = false
    for (const u of units) {
      if (u.parentId && inSubtree.has(u.parentId) && !inSubtree.has(u.id)) {
        inSubtree.add(u.id)
        grew = true
      }
    }
  }
  const candidates = units
    .filter((u) => u.legalEntityId === unit.legalEntityId && !inSubtree.has(u.id) && u.id !== unit.parentId)
    .sort((a, b) => a.name.localeCompare(b.name))
  const [parent, setParent] = useState('')
  return (
    <FormDialog
      title={intl.formatMessage(omv.moveTitle, { name: unit.name })}
      description={intl.formatMessage(om.moveLead)}
      submitLabel={intl.formatMessage(om.move)}
      valid={parent !== ''}
      onSubmit={() => onSave(parent)}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(om.newParent)} required>
        {(p) => (
          <Select
            {...p}
            value={parent}
            onChange={(e) => {
              setParent(e.target.value)
            }}
          >
            <option value="" />
            {candidates.map((u) => (
              <option key={u.id} value={u.id}>
                {intl.formatMessage(omv.unitWithCode, { name: u.name, code: u.code })}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </FormDialog>
  )
}

/** One date: the first day something no longer applies. */
export function DateDialog({
  title,
  description,
  label,
  submitLabel,
  onSave,
  onClose,
}: {
  title: string
  description?: string
  label: string
  submitLabel: string
  onSave: (date: string) => Promise<unknown>
  onClose: () => void
}) {
  const [date, setDate] = useState(today())
  return (
    <FormDialog
      title={title}
      description={description}
      submitLabel={submitLabel}
      valid={date !== ''}
      onSubmit={() => onSave(date)}
      onClose={onClose}
    >
      <Field label={label} required>
        {(p) => (
          <Input
            {...p}
            type="date"
            value={date}
            onChange={(e) => {
              setDate(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** Title, optional code and the head flag of a new or edited position. */
export function PositionFormDialog({
  title,
  initial,
  onSave,
  onClose,
}: {
  title: string
  initial?: { title: string; code?: string; head: boolean }
  onSave: (value: { title: string; code?: string; head: boolean }) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const [name, setName] = useState(initial?.title ?? '')
  const [code, setCode] = useState(initial?.code ?? '')
  const [head, setHead] = useState(initial?.head ?? false)
  return (
    <FormDialog
      title={title}
      submitLabel={intl.formatMessage(om.save)}
      valid={name.trim().length > 0}
      onSubmit={() => onSave({ title: name.trim(), ...(code.trim() ? { code: code.trim() } : {}), head })}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(om.positionTitle)} required>
        {(p) => (
          <Input
            {...p}
            value={name}
            autoComplete="off"
            onChange={(e) => {
              setName(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(om.positionCode)}>
        {(p) => (
          <Input
            {...p}
            value={code}
            autoComplete="off"
            maxLength={30}
            onChange={(e) => {
              setCode(e.target.value)
            }}
          />
        )}
      </Field>
      <label className="flex items-center gap-2 text-sm">
        <Checkbox
          checked={head}
          onChange={(e) => {
            setHead(e.target.checked)
          }}
        />
        {intl.formatMessage(om.headOfUnit)}
      </label>
    </FormDialog>
  )
}

/** A person (or a position, for reporting lines) picked from the directory, from a date. */
export function PickAndDateDialog({
  title,
  kind,
  pickLabel,
  submitLabel,
  onSave,
  onClose,
}: {
  title: string
  kind: 'user' | 'position'
  pickLabel: string
  submitLabel: string
  onSave: (picked: Picked, from: string) => Promise<unknown>
  onClose: () => void
}) {
  const intl = useIntl()
  const [picked, setPicked] = useState<Picked>()
  const [from, setFrom] = useState(today())
  return (
    <FormDialog
      title={title}
      submitLabel={submitLabel}
      valid={!!picked && from !== ''}
      onSubmit={() => (picked ? onSave(picked, from) : Promise.resolve())}
      onClose={onClose}
    >
      <Field label={pickLabel} required>
        {(p) => (
          <PeoplePicker id={p.id} describedBy={p['aria-describedby']} kind={kind} value={picked} onChange={setPicked} />
        )}
      </Field>
      <Field label={intl.formatMessage(om.fromDate)} required>
        {(p) => (
          <Input
            {...p}
            type="date"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}
