import { X } from 'lucide-react'
import { useId, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { setMemoCoauthors } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { PeoplePicker } from '@/features/org/PeoplePicker'

const m = defineMessages({
  title: { id: 'memo.coauthors', defaultMessage: 'Co-authors' },
  lead: {
    id: 'memo.coauthors.lead',
    defaultMessage: 'They edit the document and the attachments with you while it is a draft. Only you submit it.',
  },
  leadOthers: {
    id: 'memo.coauthors.leadOthers',
    defaultMessage: 'The initiator named them to help write the draft.',
  },
  none: { id: 'memo.coauthors.none', defaultMessage: 'Nobody yet.' },
  add: { id: 'memo.coauthors.add', defaultMessage: 'Add a co-author' },
  you: {
    id: 'memo.coauthors.you',
    defaultMessage: 'You are a co-author: you can edit the document and the attachments.',
  },
})

const named = defineMessages<{ remove: { name: string } }>({
  remove: { id: 'memo.coauthors.remove', defaultMessage: 'Remove {name}' },
})

/** The most co-authors a draft takes (ADR-0047). */
const MAX = 10

/**
 * Co-authors of a draft (ADR-0047): the initiator adds and removes them; everyone else sees who they are, and a
 * co-author is told what they may do.
 */
export function MemoCoauthors({
  memo,
  me,
  onChanged,
  onProblem,
}: {
  memo: MemoView
  me: string | undefined
  onChanged: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const pickerId = useId()
  const [busy, setBusy] = useState(false)
  const editable = memo.status === 'DRAFT' && memo.initiator?.id === me
  if (!editable && memo.coauthors.length === 0) return null

  const save = async (ids: string[]) => {
    setBusy(true)
    try {
      const saved = await setMemoCoauthors({ path: { id: memo.id }, body: { userIds: ids }, throwOnError: true })
      onChanged(saved.data)
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    } finally {
      setBusy(false)
    }
  }
  const ids = memo.coauthors.map((c) => c.id)

  return (
    <section aria-labelledby="memo-coauthors" className="rounded-md border p-4">
      <h2 id="memo-coauthors" className="text-base font-semibold">
        {intl.formatMessage(m.title)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(editable ? m.lead : m.leadOthers)}</p>
      {me && ids.includes(me) ? (
        <p role="note" className="mb-2 text-sm">
          {intl.formatMessage(m.you)}
        </p>
      ) : null}
      {memo.coauthors.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(m.none)}</p>
      ) : null}
      <ul className="flex flex-col gap-1">
        {memo.coauthors.map((c) => (
          <li key={c.id} className="flex items-center gap-2 text-sm">
            <span className="flex-1">{c.name}</span>
            {editable ? (
              <Button
                variant="ghost"
                size="icon"
                disabled={busy}
                aria-label={intl.formatMessage(named.remove, { name: c.name })}
                onClick={() => void save(ids.filter((id) => id !== c.id))}
              >
                <X aria-hidden="true" />
              </Button>
            ) : null}
          </li>
        ))}
      </ul>
      {editable && ids.length < MAX ? (
        <div className="mt-3 flex flex-col gap-1.5">
          <label htmlFor={pickerId} className="text-sm font-medium">
            {intl.formatMessage(m.add)}
          </label>
          <PeoplePicker
            id={pickerId}
            kind="user"
            onChange={(picked) => {
              if (picked && !ids.includes(picked.id) && picked.id !== memo.initiator?.id) void save([...ids, picked.id])
            }}
          />
        </div>
      ) : null}
    </section>
  )
}
