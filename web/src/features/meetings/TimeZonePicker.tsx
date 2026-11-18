import { useId, useMemo, useState, type KeyboardEvent } from 'react'
import { useIntl } from 'react-intl'
import { Input } from '@/components/ui/form-controls'
import { cn } from '@/lib/utils'
import { mm } from './messages'
import { ORGANISATION_ZONE, searchZones, zoneOptions } from './time'

interface Choice {
  zone: string
  label: string
  detail?: string
}

/**
 * The time-zone picker by city (flow N2): an ARIA 1.2 combobox over the IANA zones the browser knows, searchable by
 * city, zone name or the zone's long name in the reader's language ("Dhaka", "Asia/Dhaka", "Bangladesh"). The first
 * choice is the organisation's zone. Up/Down move, Enter picks, Escape closes and puts the chosen zone back.
 */
export function TimeZonePicker({
  id,
  value,
  onChange,
  invalid,
  describedBy,
}: {
  id: string
  value: string
  onChange: (zone: string) => void
  invalid?: boolean
  describedBy?: string
}) {
  const intl = useIntl()
  const listId = useId()
  const options = useMemo(() => zoneOptions(intl.locale), [intl.locale])
  const organisation: Choice = { zone: ORGANISATION_ZONE, label: intl.formatMessage(mm.orgZone) }
  const labelOf = (zone: string) =>
    zone === ORGANISATION_ZONE ? organisation.label : (options.find((o) => o.zone === zone)?.label ?? zone)
  const [text, setText] = useState(() => labelOf(value))
  const [typed, setTyped] = useState(false)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  const query = typed ? text : ''
  const matches: Choice[] = [...(query ? [] : [organisation]), ...searchZones(options, query)]

  const pick = (choice: Choice) => {
    onChange(choice.zone)
    setText(choice.label)
    setTyped(false)
    setOpen(false)
  }

  const reset = () => {
    setText(labelOf(value))
    setTyped(false)
    setOpen(false)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      if (!open) setOpen(true)
      setActive((i) => Math.min(open ? i + 1 : 0, Math.max(matches.length - 1, 0)))
      e.preventDefault()
    } else if (e.key === 'ArrowUp') {
      setActive((i) => Math.max(i - 1, 0))
      e.preventDefault()
    } else if (e.key === 'Enter' && open && matches[active]) {
      pick(matches[active])
      e.preventDefault()
    } else if (e.key === 'Escape' && open) {
      reset()
      e.preventDefault()
    }
  }

  const expanded = open && matches.length > 0
  const status = open && matches.length === 0 ? intl.formatMessage(mm.zoneNone) : undefined

  return (
    <div className="relative">
      <Input
        id={id}
        role="combobox"
        aria-expanded={expanded}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={expanded ? `${listId}-${String(active)}` : undefined}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={describedBy}
        autoComplete="off"
        placeholder={intl.formatMessage(mm.zoneSearching)}
        value={text}
        onFocus={(e) => {
          e.target.select()
        }}
        onClick={() => {
          setOpen(true)
        }}
        onChange={(e) => {
          // The first key typed over the chosen zone's name starts a new search instead of adding to the name.
          const value = e.target.value
          const fresh = !typed && value.startsWith(text) ? value.slice(text.length) : value
          setText(!typed && text.startsWith(value) ? '' : fresh)
          setTyped(true)
          setOpen(true)
          setActive(0)
        }}
        onKeyDown={onKeyDown}
        onBlur={reset}
      />
      <ul
        id={listId}
        role="listbox"
        hidden={!expanded}
        className="absolute z-10 mt-1 max-h-72 w-full overflow-auto rounded-md border bg-card p-1 shadow-lg"
      >
        {matches.map((choice, i) => (
          <li
            key={choice.zone || 'organisation'}
            id={`${listId}-${String(i)}`}
            role="option"
            aria-selected={i === active}
            onMouseDown={(e) => {
              e.preventDefault()
              pick(choice)
            }}
            className={cn(
              'min-h-11 cursor-default rounded px-2 py-1.5 text-sm',
              i === active && 'bg-accent text-accent-foreground',
            )}
          >
            <span className="font-medium">{choice.label}</span>
            {choice.detail ? <span className="block text-xs text-muted-foreground">{choice.detail}</span> : null}
          </li>
        ))}
      </ul>
      <p aria-live="polite" className="min-h-4 text-xs text-muted-foreground">
        {status}
      </p>
    </div>
  )
}
