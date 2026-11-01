import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useState, type KeyboardEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { searchDirectoryOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Input } from '@/components/ui/form-controls'
import { cn } from '@/lib/utils'

const m = defineMessages({
  hint: { id: 'picker.hint', defaultMessage: 'Type at least two letters of a name or title.' },
  none: { id: 'picker.none', defaultMessage: 'Nobody found.' },
  searching: { id: 'picker.searching', defaultMessage: 'Searching…' },
  failed: { id: 'picker.failed', defaultMessage: 'Search failed. Try again.' },
  vacant: { id: 'picker.vacant', defaultMessage: 'Vacant' },
})

export interface Picked {
  id: string
  label: string
  detail?: string
}

/**
 * The people picker (design E04 §13): an ARIA 1.2 combobox over `GET /directory/search`, debounced, for people or
 * positions. Up/Down move through the options, Enter picks, Escape closes.
 */
export function PeoplePicker({
  id,
  kind,
  value,
  onChange,
  invalid,
  describedBy,
}: {
  id?: string
  kind: 'user' | 'position'
  value?: Picked
  onChange: (picked: Picked | undefined) => void
  invalid?: boolean
  describedBy?: string
}) {
  const intl = useIntl()
  const listId = useId()
  const [text, setText] = useState(value?.label ?? '')
  const [term, setTerm] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  useEffect(() => {
    const timer = setTimeout(() => {
      setTerm(text.trim())
    }, 250)
    return () => {
      clearTimeout(timer)
    }
  }, [text])

  const ready = term.length >= 2 && term !== value?.label
  const search = useQuery({
    ...searchDirectoryOptions({ query: { q: term, kind: [kind], limit: 10 } }),
    enabled: ready,
  })
  const options: Picked[] =
    kind === 'user'
      ? (search.data?.users ?? []).map((u) => ({
          id: u.person.id,
          label: u.person.displayName,
          detail: u.position ? `${u.position.title} · ${u.position.unitName}` : u.person.email,
        }))
      : (search.data?.positions ?? []).map((p) => ({
          id: p.id,
          label: p.title,
          detail: `${p.unitName} · ${p.holder?.displayName ?? intl.formatMessage(m.vacant)}`,
        }))

  const pick = (option: Picked) => {
    onChange(option)
    setText(option.label)
    setOpen(false)
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      setOpen(true)
      setActive((i) => Math.min(i + 1, Math.max(options.length - 1, 0)))
      e.preventDefault()
    } else if (e.key === 'ArrowUp') {
      setActive((i) => Math.max(i - 1, 0))
      e.preventDefault()
    } else if (e.key === 'Enter' && open && options[active]) {
      pick(options[active])
      e.preventDefault()
    } else if (e.key === 'Escape' && open) {
      setOpen(false)
      e.preventDefault()
    }
  }

  const status = !ready
    ? intl.formatMessage(m.hint)
    : search.isPending
      ? intl.formatMessage(m.searching)
      : search.isError
        ? intl.formatMessage(m.failed)
        : options.length === 0
          ? intl.formatMessage(m.none)
          : undefined
  const expanded = open && ready && options.length > 0

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
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
          setActive(0)
          if (value) onChange(undefined)
        }}
        onKeyDown={onKeyDown}
        onBlur={() => {
          setOpen(false)
        }}
      />
      <ul
        id={listId}
        role="listbox"
        hidden={!expanded}
        className="absolute z-10 mt-1 max-h-64 w-full overflow-auto rounded-md border bg-card p-1 shadow-lg"
      >
        {options.map((option, i) => (
          <li
            key={option.id}
            id={`${listId}-${String(i)}`}
            role="option"
            aria-selected={i === active}
            onMouseDown={(e) => {
              e.preventDefault()
              pick(option)
            }}
            className={cn(
              'cursor-default rounded px-2 py-1.5 text-sm',
              i === active && 'bg-accent text-accent-foreground',
            )}
          >
            <span className="font-medium">{option.label}</span>
            {option.detail ? <span className="block text-xs text-muted-foreground">{option.detail}</span> : null}
          </li>
        ))}
      </ul>
      <p aria-live="polite" className="mt-1 min-h-4 text-xs text-muted-foreground">
        {open || !value ? status : null}
      </p>
    </div>
  )
}
