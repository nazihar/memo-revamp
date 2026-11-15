import { Link } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import type { NoteBlock, NoteDoc, NoteInline } from './note-doc'

const m = defineMessages({
  restricted: { id: 'memo.notes.memoRestricted', defaultMessage: 'Memo (restricted)' },
})

/**
 * A note's body (ADR-0049), drawn by walking its tree: no HTML from the server ever reaches the page. Links open in a
 * new tab without the opener or a referrer; a memo reference is a link only when the reader may open that memo.
 */
export function NoteRender({ doc, readableMemos = [] }: { doc: NoteDoc; readableMemos?: string[] }) {
  return (
    <div className="note-body flex flex-col gap-1 text-sm">{doc.content.map((b, i) => block(b, i, readableMemos))}</div>
  )
}

function block(node: NoteBlock, key: number, readable: string[]): ReactNode {
  switch (node.type) {
    case 'paragraph':
      return (
        <p key={key} className="whitespace-pre-wrap">
          {(node.content ?? []).map((n, i) => inline(n, i, readable))}
        </p>
      )
    case 'bulletList':
      return (
        <ul key={key} className="list-disc ps-5">
          {node.content.map((item, i) => (
            <li key={i}>{item.content.map((b, j) => block(b, j, readable))}</li>
          ))}
        </ul>
      )
    case 'orderedList':
      return (
        <ol key={key} className="list-decimal ps-5" start={node.attrs?.start}>
          {node.content.map((item, i) => (
            <li key={i}>{item.content.map((b, j) => block(b, j, readable))}</li>
          ))}
        </ol>
      )
  }
}

function inline(node: NoteInline, key: number, readable: string[]): ReactNode {
  switch (node.type) {
    case 'text':
      return <Text key={key} node={node} />
    case 'hardBreak':
      return <br key={key} />
    case 'mention':
      return (
        <span key={key} className="rounded bg-muted px-1 font-medium" data-mention={node.attrs.id}>
          {atName(node.attrs.label)}
        </span>
      )
    case 'memoRef':
      return (
        <MemoRef key={key} id={node.attrs.id} label={node.attrs.label} readable={readable.includes(node.attrs.id)} />
      )
  }
}

function Text({ node }: { node: Extract<NoteInline, { type: 'text' }> }) {
  let content: ReactNode = node.text
  const marks = node.marks ?? []
  if (marks.some((mk) => mk.type === 'bold')) content = <strong>{content}</strong>
  if (marks.some((mk) => mk.type === 'italic')) content = <em>{content}</em>
  const link = marks.find((mk) => mk.type === 'link')?.attrs?.href
  if (link?.startsWith('https://')) {
    content = (
      <a href={link} target="_blank" rel="noopener noreferrer nofollow" className="underline">
        {content}
      </a>
    )
  }
  return <>{content}</>
}

function MemoRef({ id, label, readable }: { id: string; label: string; readable: boolean }) {
  const intl = useIntl()
  if (!readable) return <span className="text-muted-foreground">{intl.formatMessage(m.restricted)}</span>
  return (
    <Link to="/memos/$memoId" params={{ memoId: id }} className="font-medium underline">
      {label}
    </Link>
  )
}

/** A mention as text: the name after an at sign. */
function atName(label: string): string {
  return '@' + label
}
