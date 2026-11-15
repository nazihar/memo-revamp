import Mention from '@tiptap/extension-mention'
import { EditorContent, useEditor, type Editor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import type { SuggestionKeyDownProps, SuggestionProps } from '@tiptap/suggestion'
import { Bold, Italic, Link2, List, ListOrdered, AtSign } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { searchDirectory } from '@/api/generated/sdk.gen'
import { cn } from '@/lib/utils'
import { MAX_TEXT, plainText, toNoteDoc, type NoteDoc } from './note-doc'

const m = defineMessages({
  toolbar: { id: 'memo.notes.toolbar', defaultMessage: 'Formatting' },
  bold: { id: 'memo.notes.bold', defaultMessage: 'Bold' },
  italic: { id: 'memo.notes.italic', defaultMessage: 'Italic' },
  bullets: { id: 'memo.notes.bullets', defaultMessage: 'Bulleted list' },
  numbers: { id: 'memo.notes.numbers', defaultMessage: 'Numbered list' },
  link: { id: 'memo.notes.link', defaultMessage: 'Link' },
  linkPrompt: { id: 'memo.notes.linkPrompt', defaultMessage: 'Web address (https://…)' },
  linkApply: { id: 'memo.notes.linkApply', defaultMessage: 'Add link' },
  mention: { id: 'memo.notes.mention', defaultMessage: 'Mention someone' },
  people: { id: 'memo.notes.people', defaultMessage: 'People' },
  noPeople: { id: 'memo.notes.noPeople', defaultMessage: 'Type at least two letters of a name.' },
})

const mv = defineMessages<{
  count: { count: number; max: number }
}>({
  count: { id: 'memo.notes.count', defaultMessage: '{count, number} / {max, number}' },
})

interface Person {
  id: string
  label: string
}

interface Picking {
  items: Person[]
  index: number
  command: (p: { id: string; label: string }) => void
}

/**
 * The note editor (ADR-0049): Tiptap with exactly the `noteDoc/v1` nodes, no injected styles (our CSP has no inline
 * styles; the core rules are in our stylesheet), `https:` links only, and mentions picked from the directory through
 * an ARIA listbox. [onChange] gets the note as `noteDoc/v1`.
 */
export default function NoteEditor({
  initial,
  label,
  onChange,
  autoFocus = false,
}: {
  initial?: NoteDoc
  label: string
  onChange: (doc: NoteDoc) => void
  autoFocus?: boolean
}) {
  const intl = useIntl()
  const listId = useId()
  const [picking, setPicking] = useState<Picking | undefined>()
  const pickingRef = useRef<Picking | undefined>(undefined)
  const [linking, setLinking] = useState<string | undefined>()
  const [count, setCount] = useState(initial ? plainText(initial).length : 0)
  useEffect(() => {
    pickingRef.current = picking
  }, [picking])

  const editor = useEditor({
    injectCSS: false,
    autofocus: autoFocus,
    extensions: [
      StarterKit.configure({
        heading: false,
        blockquote: false,
        code: false,
        codeBlock: false,
        horizontalRule: false,
        strike: false,
        underline: false,
        dropcursor: false,
        gapcursor: false,
        trailingNode: false,
        link: {
          openOnClick: false,
          autolink: true,
          protocols: ['https'],
          defaultProtocol: 'https',
          isAllowedUri: (url) => url.startsWith('https://'),
          HTMLAttributes: { rel: 'noopener noreferrer nofollow', target: '_blank' },
        },
      }),
      Mention.configure({
        HTMLAttributes: { class: 'rounded bg-muted px-1 font-medium' },
        renderText: ({ node }) => `@${String(node.attrs.label)}`,
        suggestion: {
          char: '@',
          // Names have spaces: "@Head of Cr" keeps searching.
          allowSpaces: true,
          items: async ({ query }) => {
            if (query.trim().length < 2) return []
            const found = await searchDirectory({ query: { q: query, kind: ['user'], limit: 8 } })
            return (found.data?.users ?? []).map((u) => ({ id: u.person.id, label: u.person.displayName }))
          },
          render: () => ({
            onStart: (props: SuggestionProps<Person>) => {
              setPicking({ items: props.items, index: 0, command: props.command })
            },
            onUpdate: (props: SuggestionProps<Person>) => {
              setPicking({ items: props.items, index: 0, command: props.command })
            },
            onKeyDown: ({ event }: SuggestionKeyDownProps) => pick(event),
            onExit: () => {
              setPicking(undefined)
            },
          }),
        },
      }),
    ],
    content: initial,
    editorProps: {
      attributes: {
        'aria-label': label,
        'aria-multiline': 'true',
        role: 'textbox',
        class:
          'ProseMirror min-h-28 rounded-md border px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-ring',
      },
    },
    onUpdate: ({ editor: e }) => {
      const doc = toNoteDoc(e.getJSON())
      setCount(plainText(doc).length)
      onChange(doc)
    },
  })

  /** Arrow keys move through the people found, Enter picks, Escape closes; other keys type. */
  function pick(event: KeyboardEvent): boolean {
    const current = pickingRef.current
    if (!current || current.items.length === 0) return false
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const step = event.key === 'ArrowDown' ? 1 : -1
      setPicking({ ...current, index: (current.index + step + current.items.length) % current.items.length })
      return true
    }
    if (event.key === 'Enter') {
      const chosen = current.items[current.index]
      if (chosen) current.command({ id: chosen.id, label: chosen.label })
      return true
    }
    if (event.key === 'Escape') {
      setPicking(undefined)
      return true
    }
    return false
  }

  useEffect(
    () => () => {
      editor.destroy()
    },
    [editor],
  )

  return (
    <div className="flex flex-col gap-2">
      <Toolbar
        editor={editor}
        onLink={() => {
          setLinking((editor.getAttributes('link').href as string | undefined) ?? 'https://')
        }}
      />
      {linking !== undefined ? (
        <form
          className="flex gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (linking.startsWith('https://'))
              editor.chain().focus().extendMarkRange('link').setLink({ href: linking }).run()
            setLinking(undefined)
          }}
        >
          <input
            aria-label={intl.formatMessage(m.linkPrompt)}
            className="flex-1 rounded-md border px-2 py-1 text-sm"
            value={linking}
            onChange={(e) => {
              setLinking(e.target.value)
            }}
          />
          <button type="submit" className="rounded-md border px-2 text-sm">
            {intl.formatMessage(m.linkApply)}
          </button>
        </form>
      ) : null}
      <div className="relative">
        <EditorContent
          editor={editor}
          aria-controls={picking ? listId : undefined}
          aria-expanded={picking ? true : undefined}
        />
        {picking ? (
          <ul
            id={listId}
            role="listbox"
            aria-label={intl.formatMessage(m.people)}
            className="absolute inset-x-0 top-full z-10 mt-1 max-h-48 overflow-auto rounded-md border bg-popover shadow"
          >
            {picking.items.length === 0 ? (
              <li className="px-3 py-2 text-sm text-muted-foreground">{intl.formatMessage(m.noPeople)}</li>
            ) : (
              picking.items.map((p, i) => (
                <li
                  key={p.id}
                  role="option"
                  aria-selected={i === picking.index}
                  className={cn('cursor-pointer px-3 py-2 text-sm', i === picking.index && 'bg-accent')}
                  onMouseDown={(e) => {
                    e.preventDefault()
                    picking.command({ id: p.id, label: p.label })
                  }}
                >
                  {p.label}
                </li>
              ))
            )}
          </ul>
        ) : null}
      </div>
      <p
        className={cn('text-end text-xs text-muted-foreground', count > MAX_TEXT && 'text-destructive')}
        aria-live="polite"
      >
        {intl.formatMessage(mv.count, { count, max: MAX_TEXT })}
      </p>
    </div>
  )
}

function Toolbar({ editor, onLink }: { editor: Editor | null; onLink: () => void }) {
  const intl = useIntl()
  const button = (pressed: boolean, label: string, icon: React.ReactNode, run: () => void) => (
    <button
      type="button"
      aria-label={label}
      aria-pressed={pressed}
      title={label}
      className={cn('rounded p-1.5 hover:bg-accent', pressed && 'bg-accent')}
      onClick={run}
      disabled={!editor}
    >
      {icon}
    </button>
  )
  return (
    <div role="toolbar" aria-label={intl.formatMessage(m.toolbar)} className="flex flex-wrap gap-1">
      {button(
        editor?.isActive('bold') ?? false,
        intl.formatMessage(m.bold),
        <Bold className="size-4" aria-hidden="true" />,
        () => editor?.chain().focus().toggleBold().run(),
      )}
      {button(
        editor?.isActive('italic') ?? false,
        intl.formatMessage(m.italic),
        <Italic className="size-4" aria-hidden="true" />,
        () => editor?.chain().focus().toggleItalic().run(),
      )}
      {button(
        editor?.isActive('bulletList') ?? false,
        intl.formatMessage(m.bullets),
        <List className="size-4" aria-hidden="true" />,
        () => editor?.chain().focus().toggleBulletList().run(),
      )}
      {button(
        editor?.isActive('orderedList') ?? false,
        intl.formatMessage(m.numbers),
        <ListOrdered className="size-4" aria-hidden="true" />,
        () => editor?.chain().focus().toggleOrderedList().run(),
      )}
      {button(
        editor?.isActive('link') ?? false,
        intl.formatMessage(m.link),
        <Link2 className="size-4" aria-hidden="true" />,
        onLink,
      )}
      {button(false, intl.formatMessage(m.mention), <AtSign className="size-4" aria-hidden="true" />, () =>
        editor?.chain().focus().insertContent('@').run(),
      )}
    </div>
  )
}
