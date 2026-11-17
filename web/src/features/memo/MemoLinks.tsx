import { Link } from '@tanstack/react-router'
import { X } from 'lucide-react'
import { useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { linkMemo, unlinkMemo } from '@/api/generated/sdk.gen'
import type { LinkRequest, MemoLinkView, MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { newIdempotencyKey } from '@/lib/idempotency'
import { referenceOf } from './memo-fields'
import { statusLabels } from './memo-messages'
import { statusTone } from './memo-status'

type LinkKind = NonNullable<LinkRequest['kind']>

const lm = defineMessages({
  links: { id: 'memo.links', defaultMessage: 'Linked memos' },
  linkedFrom: { id: 'memo.links.from', defaultMessage: 'Linked from' },
  none: { id: 'memo.links.none', defaultMessage: 'None.' },
  number: { id: 'memo.links.number', defaultMessage: 'Memo number' },
  numberHint: { id: 'memo.links.number.hint', defaultMessage: 'The number of a memo you can open.' },
  kind: { id: 'memo.links.kind', defaultMessage: 'How it relates' },
  add: { id: 'memo.links.add', defaultMessage: 'Link' },
  numberRequired: { id: 'memo.links.number.required', defaultMessage: 'Enter a memo number.' },
  added: { id: 'memo.links.added', defaultMessage: 'Linked.' },
  removed: { id: 'memo.links.removed', defaultMessage: 'Link removed.' },
  REFERS_TO: { id: 'memo.links.kind.refersTo', defaultMessage: 'Refers to' },
  SUPERSEDES: { id: 'memo.links.kind.supersedes', defaultMessage: 'Supersedes' },
  FOLLOWS_UP: { id: 'memo.links.kind.followsUp', defaultMessage: 'Follows up' },
})

/** How the linking memo reads from the other side. */
const fromLabels = defineMessages({
  REFERS_TO: { id: 'memo.links.from.refersTo', defaultMessage: 'Refers to this memo' },
  SUPERSEDES: { id: 'memo.links.from.supersedes', defaultMessage: 'Supersedes this memo' },
  FOLLOWS_UP: { id: 'memo.links.from.followsUp', defaultMessage: 'Follows up this memo' },
})

const removeLabel = defineMessages<{ remove: { reference: string } }>({
  remove: { id: 'memo.links.remove', defaultMessage: 'Remove the link to {reference}' },
})

const KINDS: LinkKind[] = ['REFERS_TO', 'SUPERSEDES', 'FOLLOWS_UP']

/**
 * The memo's links (ADR-0044 §links, ux-review B3): memos it refers to, supersedes or follows up, added by number, and
 * the memos that link to it. Only memos the reader may open are listed; whoever added a link may remove it while the
 * memo is a draft or returned.
 */
export function MemoLinks({
  memo,
  onChanged,
  onProblem,
  onDone,
}: {
  memo: MemoView
  onChanged: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
  onDone: (message: string) => void
}) {
  const intl = useIntl()
  const [reference, setReference] = useState('')
  const [kind, setKind] = useState<LinkKind>('REFERS_TO')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const [key, setKey] = useState(() => newIdempotencyKey())

  if (!memo.canLink && memo.links.length === 0 && memo.linkedFrom.length === 0) return null

  const add = async (e: SyntheticEvent) => {
    e.preventDefault()
    if (!reference.trim()) {
      setError(intl.formatMessage(lm.numberRequired))
      return
    }
    setBusy(true)
    try {
      const next = await linkMemo({
        path: { id: memo.id },
        headers: { 'Idempotency-Key': key },
        body: { targetReference: reference.trim(), kind },
        throwOnError: true,
      })
      setReference('')
      setError(undefined)
      setKey(newIdempotencyKey())
      onChanged(next.data)
      onDone(intl.formatMessage(lm.added))
    } catch (problem) {
      const field = isProblem(problem) ? problem.errors?.find((p) => p.field === 'targetReference') : undefined
      if (field) setError(field.message)
      else onProblem(isProblem(problem) ? problem : undefined)
    } finally {
      setBusy(false)
    }
  }

  const remove = async (link: MemoLinkView) => {
    setBusy(true)
    try {
      const next = await unlinkMemo({ path: { id: memo.id, linkId: link.id }, throwOnError: true })
      onChanged(next.data)
      onDone(intl.formatMessage(lm.removed))
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="memo-links" className="rounded-md border p-4">
      <h2 id="memo-links" className="mb-3 text-base font-semibold">
        {intl.formatMessage(lm.links)}
      </h2>
      <LinkList
        links={memo.links}
        kindLabel={(l) => intl.formatMessage(lm[l.kind])}
        onRemove={(l) => void remove(l)}
        busy={busy}
      />
      {memo.canLink ? (
        <form noValidate onSubmit={(e) => void add(e)} className="mt-3 flex flex-wrap items-end gap-2">
          <Field label={intl.formatMessage(lm.kind)} className="w-40">
            {(p) => (
              <Select
                {...p}
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value as LinkKind)
                }}
              >
                {KINDS.map((k) => (
                  <option key={k} value={k}>
                    {intl.formatMessage(lm[k])}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field
            label={intl.formatMessage(lm.number)}
            hint={intl.formatMessage(lm.numberHint)}
            error={error}
            className="min-w-48 flex-1"
          >
            {(p) => (
              <Input
                {...p}
                dir="ltr"
                maxLength={100}
                value={reference}
                onChange={(e) => {
                  setReference(e.target.value)
                }}
              />
            )}
          </Field>
          <Button type="submit" variant="outline" disabled={busy}>
            {intl.formatMessage(lm.add)}
          </Button>
        </form>
      ) : null}
      {memo.linkedFrom.length > 0 ? (
        <>
          <h3 className="mt-4 mb-2 text-sm font-semibold">{intl.formatMessage(lm.linkedFrom)}</h3>
          <LinkList links={memo.linkedFrom} kindLabel={(l) => intl.formatMessage(fromLabels[l.kind])} />
        </>
      ) : null}
    </section>
  )
}

function LinkList({
  links,
  kindLabel,
  onRemove,
  busy,
}: {
  links: MemoLinkView[]
  kindLabel: (link: MemoLinkView) => string
  onRemove?: (link: MemoLinkView) => void
  busy?: boolean
}) {
  const intl = useIntl()
  if (links.length === 0) return <p className="text-sm text-muted-foreground">{intl.formatMessage(lm.none)}</p>
  return (
    <ul className="flex flex-col gap-2 text-sm">
      {links.map((link) => (
        <li key={link.id} className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground">{kindLabel(link)}</span>
          <Link to="/memos/$memoId" params={{ memoId: link.memoId }} className="font-medium underline">
            {referenceOf(intl, link.reference)}
          </Link>
          <span className="min-w-0 flex-1 truncate">{link.subject}</span>
          <Badge tone={statusTone[link.status]}>{intl.formatMessage(statusLabels[link.status])}</Badge>
          {onRemove && link.removable ? (
            <Button
              variant="ghost"
              size="icon"
              disabled={busy}
              aria-label={intl.formatMessage(removeLabel.remove, { reference: referenceOf(intl, link.reference) })}
              onClick={() => {
                onRemove(link)
              }}
            >
              <X aria-hidden="true" />
            </Button>
          ) : null}
        </li>
      ))}
    </ul>
  )
}
