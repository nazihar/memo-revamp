/**
 * The note format `noteDoc/v1` (ADR-0049) on the web: the ProseMirror JSON Tiptap writes, cut down to exactly what the
 * server accepts. Tiptap adds attributes of its own (a link's `target` and `rel`, a mention's `mentionSuggestionChar`,
 * `null` values); [toNoteDoc] keeps only the format's keys, so what the editor holds and what is posted never differ in
 * content, and the server's strict check never trips on editor details.
 */
export interface NoteMark {
  type: 'bold' | 'italic' | 'link'
  attrs?: { href: string }
}

export type NoteInline =
  | { type: 'text'; text: string; marks?: NoteMark[] }
  | { type: 'hardBreak' }
  | { type: 'mention'; attrs: { id: string; label: string } }
  | { type: 'memoRef'; attrs: { id: string; label: string } }

export type NoteBlock =
  | { type: 'paragraph'; content?: NoteInline[] }
  | { type: 'bulletList'; content: NoteListItem[] }
  | { type: 'orderedList'; attrs?: { start: number }; content: NoteListItem[] }

export interface NoteListItem {
  type: 'listItem'
  content: NoteBlock[]
}

export interface NoteDoc {
  type: 'doc'
  content: NoteBlock[]
}

/** Limits the server enforces; the editor shows the text count against [MAX_TEXT]. */
export const MAX_TEXT = 4000
export const MAX_FILES = 5

interface Json {
  type?: unknown
  attrs?: Record<string, unknown>
  content?: unknown
  marks?: unknown
  text?: unknown
}

function asNodes(value: unknown): Json[] {
  return Array.isArray(value) ? (value as Json[]) : []
}

function mark(raw: Json): NoteMark | undefined {
  if (raw.type === 'bold' || raw.type === 'italic') return { type: raw.type }
  if (raw.type === 'link' && typeof raw.attrs?.href === 'string')
    return { type: 'link', attrs: { href: raw.attrs.href } }
  return undefined
}

function inline(raw: Json): NoteInline | undefined {
  switch (raw.type) {
    case 'text': {
      if (typeof raw.text !== 'string' || raw.text.length === 0) return undefined
      const marks = asNodes(raw.marks)
        .map(mark)
        .filter((m): m is NoteMark => m !== undefined)
      return marks.length > 0 ? { type: 'text', text: raw.text, marks } : { type: 'text', text: raw.text }
    }
    case 'hardBreak':
      return { type: 'hardBreak' }
    case 'mention':
    case 'memoRef': {
      const id = raw.attrs?.id
      const label = raw.attrs?.label
      return typeof id === 'string' && typeof label === 'string' ? { type: raw.type, attrs: { id, label } } : undefined
    }
    default:
      return undefined
  }
}

function block(raw: Json): NoteBlock | undefined {
  switch (raw.type) {
    case 'paragraph': {
      const content = asNodes(raw.content)
        .map(inline)
        .filter((n): n is NoteInline => n !== undefined)
      return content.length > 0 ? { type: 'paragraph', content } : { type: 'paragraph' }
    }
    case 'bulletList':
    case 'orderedList': {
      const items = asNodes(raw.content).map((item) => ({
        type: 'listItem' as const,
        content: asNodes(item.content)
          .map(block)
          .filter((b): b is NoteBlock => b !== undefined),
      }))
      if (items.length === 0) return undefined
      const start = raw.attrs?.start
      if (raw.type === 'orderedList' && typeof start === 'number' && start !== 1) {
        return { type: 'orderedList', attrs: { start }, content: items }
      }
      return { type: raw.type, content: items }
    }
    default:
      return undefined
  }
}

/** The editor's JSON as `noteDoc/v1`. */
export function toNoteDoc(editorJson: unknown): NoteDoc {
  const root = (editorJson ?? {}) as Json
  return {
    type: 'doc',
    content: asNodes(root.content)
      .map(block)
      .filter((b): b is NoteBlock => b !== undefined),
  }
}

function inlineText(node: NoteInline): string {
  switch (node.type) {
    case 'text':
      return node.text
    case 'hardBreak':
      return '\n'
    case 'mention':
      return `@${node.attrs.label}`
    case 'memoRef':
      return node.attrs.label
  }
}

function blockText(node: NoteBlock): string {
  if (node.type === 'paragraph') return (node.content ?? []).map(inlineText).join('')
  return node.content.map((item) => item.content.map(blockText).join('\n')).join('\n')
}

/** The plain text the server derives (for counting and for empty checks). */
export function plainText(doc: NoteDoc): string {
  return doc.content.map(blockText).join('\n').trim()
}

/** The people a note mentions, once each. */
export function mentions(doc: NoteDoc): string[] {
  const found = new Set<string>()
  const walk = (blocks: NoteBlock[]) => {
    for (const b of blocks) {
      if (b.type === 'paragraph') {
        for (const n of b.content ?? []) if (n.type === 'mention') found.add(n.attrs.id)
      } else {
        for (const item of b.content) walk(item.content)
      }
    }
  }
  walk(doc.content)
  return [...found]
}

/** A one-paragraph note, for older remarks shown in the same way. */
export function noteOfText(text: string): NoteDoc {
  return {
    type: 'doc',
    content: text
      .split('\n')
      .map((line) => (line ? { type: 'paragraph', content: [{ type: 'text', text: line }] } : { type: 'paragraph' })),
  }
}
