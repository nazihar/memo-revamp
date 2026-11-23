import { X } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import type { ActionPerson } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import type { OwnerChoice } from './owner'
import { ao } from './messages'

/**
 * The owner (flow AJ2, design §1.3): "A person" or "A position" as two 44 px radios, then the directory's picker for
 * that kind; a position shows who holds it now. Anyone in the organisation may own an action, member or not.
 */
export function OwnerPicker({
  value,
  onChange,
  error,
  name = 'owner',
}: {
  value: OwnerChoice
  onChange: (next: OwnerChoice) => void
  error?: string | undefined
  name?: string
}) {
  const intl = useIntl()
  const pickerId = useId()
  const hintId = useId()
  const errorId = useId()
  return (
    <fieldset data-field={name} className="flex flex-col gap-2" aria-describedby={hintId}>
      <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ao.owner)}</legend>
      <div className="flex flex-wrap gap-2">
        {(['PERSON', 'POSITION'] as const).map((kind) => (
          <label
            key={kind}
            className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md border px-3 has-[:checked]:border-primary"
          >
            <input
              type="radio"
              name={`${name}-kind`}
              className="size-5 accent-primary"
              checked={value.kind === kind}
              onChange={() => {
                onChange({ kind })
              }}
            />
            {intl.formatMessage(kind === 'PERSON' ? ao.ownerPerson : ao.ownerPosition)}
          </label>
        ))}
      </div>
      <label htmlFor={pickerId} className="text-sm font-medium">
        {intl.formatMessage(ao.ownerWho)}
      </label>
      <div>
        <PeoplePicker
          key={value.kind}
          id={pickerId}
          kind={value.kind === 'PERSON' ? 'user' : 'position'}
          {...(value.picked ? { value: value.picked } : {})}
          onChange={(picked) => {
            onChange(picked ? { kind: value.kind, picked } : { kind: value.kind })
          }}
          invalid={Boolean(error)}
          describedBy={error ? errorId : undefined}
        />
      </div>
      {value.picked?.detail ? (
        <p className="text-sm">{intl.formatMessage(ao.ownerNow, { owner: value.picked.detail })}</p>
      ) : null}
      <p id={hintId} className="text-xs text-muted-foreground">
        {intl.formatMessage(ao.ownerHint)}
      </p>
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </fieldset>
  )
}

/**
 * The people also involved (flow AJ2): up to five, each with a 44 px "Remove {name}", added one at a time through the
 * directory's picker, and announced as they come and go.
 */
export function ContributorsField({
  value,
  onChange,
  error,
}: {
  value: ActionPerson[]
  onChange: (next: ActionPerson[]) => void
  error?: string | undefined
}) {
  const intl = useIntl()
  const pickerId = useId()
  const hintId = useId()
  const errorId = useId()
  const [picked, setPicked] = useState<Picked>()
  const [reset, setReset] = useState(0)
  const [said, setSaid] = useState('')
  const full = value.length >= 5
  const add = () => {
    if (!picked || value.some((p) => p.id === picked.id)) return
    onChange([...value, { id: picked.id, name: picked.label }])
    setSaid(intl.formatMessage(ao.contributorAdded, { name: picked.label }))
    setPicked(undefined)
    setReset((n) => n + 1)
  }
  return (
    <fieldset data-field="contributors" className="flex flex-col gap-2" aria-describedby={hintId}>
      <legend className="mb-1 text-sm font-medium">
        {intl.formatMessage(ao.contributors)}
        <span className="ms-1 font-normal text-muted-foreground">{intl.formatMessage(ao.optional)}</span>
      </legend>
      {value.length ? (
        <ul className="flex flex-col gap-1">
          {value.map((p) => (
            <li key={p.id} className="flex min-h-11 items-center justify-between gap-2 rounded-md border px-3">
              <span>{p.name}</span>
              <Button
                type="button"
                variant="ghost"
                className="min-h-11 min-w-11"
                aria-label={intl.formatMessage(ao.removePerson, { name: p.name })}
                onClick={() => {
                  onChange(value.filter((x) => x.id !== p.id))
                  setSaid(intl.formatMessage(ao.contributorRemoved, { name: p.name }))
                }}
              >
                <X aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      ) : null}
      {full ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ao.contributorsFull)}</p>
      ) : (
        <div className="flex flex-wrap items-start gap-2">
          <div className="min-w-0 flex-[1_1_14rem]">
            <label htmlFor={pickerId} className="sr-only">
              {intl.formatMessage(ao.findPerson)}
            </label>
            <PeoplePicker
              key={reset}
              id={pickerId}
              kind="user"
              {...(picked ? { value: picked } : {})}
              onChange={setPicked}
              invalid={Boolean(error)}
              describedBy={error ? errorId : undefined}
            />
          </div>
          <Button type="button" variant="outline" className="min-h-11" disabled={!picked} onClick={add}>
            {intl.formatMessage(ao.addPerson)}
          </Button>
        </div>
      )}
      <p id={hintId} className="text-xs text-muted-foreground">
        {intl.formatMessage(ao.contributorsHint)}
      </p>
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
      <p aria-live="polite" className="sr-only">
        {said}
      </p>
    </fieldset>
  )
}
