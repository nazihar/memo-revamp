import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { useIntl } from 'react-intl'
import { listSavedSearchesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { suggestSearch } from '@/api/generated/sdk.gen'
import { Input } from '@/components/ui/form-controls'
import { useSession } from '@/features/auth/session'
import { cn } from '@/lib/utils'
import { useRecentSearches } from './recent-searches'
import { sbm } from './search-messages'
import { hashOf, locationOf } from './search-params'

/** Suggestions start once the word being typed has this many characters (design E05 §3). */
const MIN_PREFIX = 3
const DEBOUNCE_MS = 300
const MAX_SAVED = 5

interface Option {
  key: string
  label: string
  detail?: string
  run: () => void
}

interface Group {
  key: string
  label?: string
  options: Option[]
}

/** Typing "/" anywhere but in a field goes to the box (flow H1), as on many sites with search. */
function isTyping(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || target.closest('input, textarea, select, [contenteditable="true"]') !== null)
  )
}

/**
 * The top bar's search box (flow H1): an ARIA 1.2 combobox. While typing it suggests memos (debounced; the last word as
 * a prefix), and it lists recent words (this session only) and saved searches. ↓/↑ move, Enter opens the chosen option
 * or searches for the text, Escape closes the list.
 */
export function SearchBox() {
  const intl = useIntl()
  const navigate = useNavigate()
  const session = useSession()
  const signedIn = session.data?.state === 'AUTHENTICATED'
  const recent = useRecentSearches(session.data?.user?.id)
  const input = useRef<HTMLInputElement>(null)
  const listId = useId()
  const [text, setText] = useState('')
  const [term, setTerm] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)

  useEffect(() => {
    const timer = setTimeout(() => {
      setTerm(text.trim())
    }, DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
    }
  }, [text])

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented || isTyping(e.target)) return
      if (!input.current || document.querySelector('dialog[open]')) return
      e.preventDefault()
      input.current.focus()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [])

  const lastWord = term.split(/\s+/).pop() ?? ''
  const suggest = useQuery({
    // A POST, so the words never go in a URL; the key holds them in memory only.
    queryKey: ['memoSearchSuggest', term],
    queryFn: async ({ signal }) => {
      const { data } = await suggestSearch({ body: { q: term }, signal, throwOnError: true })
      return data
    },
    enabled: signedIn && open && lastWord.length >= MIN_PREFIX,
    staleTime: 30_000,
    retry: false,
  })
  const saved = useQuery({ ...listSavedSearchesOptions(), enabled: signedIn && open, staleTime: 30_000 })

  if (!signedIn) return null

  const close = () => {
    setOpen(false)
    setActive(-1)
  }
  const searchFor = (q: string) => {
    close()
    void navigate({ to: '/search', search: {}, hash: hashOf(q) })
  }

  const needle = text.trim()
  const lower = needle.toLowerCase()
  const groups: Group[] = []
  if (needle) {
    groups.push({
      key: 'search',
      options: [
        {
          key: 'search',
          label: intl.formatMessage(sbm.searchFor, { text: needle }),
          run: () => {
            searchFor(needle)
          },
        },
      ],
    })
  }
  if (needle && term === needle && lastWord.length >= MIN_PREFIX && suggest.data?.length) {
    groups.push({
      key: 'memos',
      label: intl.formatMessage(sbm.groupMemos),
      options: suggest.data.map((s) => ({
        key: `memo-${s.memoId}`,
        label: s.subject,
        detail: s.number ?? undefined,
        run: () => {
          close()
          setText('')
          void navigate({ to: '/memos/$memoId', params: { memoId: s.memoId } })
        },
      })),
    })
  }
  const recentShown = recent.filter((t) => t !== needle && t.toLowerCase().includes(lower))
  if (recentShown.length > 0) {
    groups.push({
      key: 'recent',
      label: intl.formatMessage(sbm.groupRecent),
      options: recentShown.map((t) => ({
        key: `recent-${t}`,
        label: t,
        run: () => {
          setText(t)
          searchFor(t)
        },
      })),
    })
  }
  const savedShown = (saved.data ?? []).filter((s) => s.name.toLowerCase().includes(lower)).slice(0, MAX_SAVED)
  if (savedShown.length > 0 || (!needle && saved.isSuccess)) {
    groups.push({
      key: 'saved',
      label: intl.formatMessage(sbm.groupSaved),
      options: [
        ...savedShown.map((s) => ({
          key: `saved-${s.id}`,
          label: s.name,
          run: () => {
            close()
            setText(s.criteria.q ?? '')
            void navigate({ to: '/search', ...locationOf(s.criteria) })
          },
        })),
        {
          key: 'all-saved',
          label: intl.formatMessage(sbm.allSaved),
          run: () => {
            close()
            void navigate({ to: '/search/saved' })
          },
        },
      ],
    })
  }
  const options = groups.flatMap((g) => g.options)
  const expanded = open && options.length > 0
  const optionId = (index: number) => `${listId}-${String(index)}`

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      setOpen(true)
      setActive((i) => Math.min(i + 1, options.length - 1))
      e.preventDefault()
    } else if (e.key === 'ArrowUp') {
      setActive((i) => Math.max(i - 1, -1))
      e.preventDefault()
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const chosen = expanded && active >= 0 ? options[active] : undefined
      if (chosen) chosen.run()
      else if (needle) searchFor(needle)
    } else if (e.key === 'Escape') {
      if (expanded) close()
      else setText('')
      e.preventDefault()
    }
  }

  let index = 0
  return (
    <form
      role="search"
      aria-label={intl.formatMessage(sbm.label)}
      className="relative w-full"
      onSubmit={(e) => {
        e.preventDefault()
      }}
    >
      <Search
        aria-hidden="true"
        className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
      />
      <Input
        ref={input}
        type="search"
        role="combobox"
        aria-label={intl.formatMessage(sbm.label)}
        aria-expanded={expanded}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={expanded && active >= 0 ? optionId(active) : undefined}
        aria-keyshortcuts="/"
        placeholder={intl.formatMessage(sbm.placeholder)}
        autoComplete="off"
        enterKeyHint="search"
        className="ps-9"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
          setActive(-1)
        }}
        onFocus={() => {
          setOpen(true)
        }}
        onBlur={close}
        onKeyDown={onKeyDown}
      />
      <div
        id={listId}
        role="listbox"
        aria-label={intl.formatMessage(sbm.label)}
        hidden={!expanded}
        className="absolute inset-x-0 top-full z-20 mt-1 max-h-[70dvh] min-w-72 overflow-auto rounded-md border bg-card p-1 text-card-foreground shadow-lg"
      >
        {groups.map((group) => {
          const items = group.options.map((option) => {
            const i = index++
            return (
              <div
                key={option.key}
                id={optionId(i)}
                role="option"
                aria-selected={i === active}
                onMouseDown={(e) => {
                  // Keep the focus in the box, so blur does not close the list before the choice.
                  e.preventDefault()
                  option.run()
                }}
                className={cn(
                  'flex min-h-10 cursor-default flex-col justify-center rounded px-2 py-1.5 text-sm',
                  i === active && 'bg-accent text-accent-foreground',
                )}
              >
                <span className="truncate">{option.label}</span>
                {option.detail ? <span className="text-xs text-muted-foreground">{option.detail}</span> : null}
              </div>
            )
          })
          if (!group.label) return items
          const labelId = `${listId}-${group.key}`
          return (
            <div key={group.key} role="group" aria-labelledby={labelId}>
              <div
                id={labelId}
                role="presentation"
                className="px-2 pb-1 pt-2 text-xs font-semibold text-muted-foreground"
              >
                {group.label}
              </div>
              {items}
            </div>
          )
        })}
      </div>
      <p aria-live="polite" className="sr-only">
        {expanded ? intl.formatMessage(sbm.options, { count: options.length }) : null}
      </p>
    </form>
  )
}
