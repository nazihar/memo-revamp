import { X } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { declareMemoInterest, removeMemoInterest } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Textarea } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { mm, mmv } from './memo-messages'

const REASON_MAX = 2000

/**
 * Declared interests (design E04 §4.4, ux-review flow 3): the initiator lists who has an interest in the memo while it is
 * theirs to edit; those people cannot approve it. Everyone else who can see the memo reads the list.
 */
export function MemoInterests({
  memo,
  editable,
  onChanged,
  onProblem,
}: {
  memo: MemoView
  editable: boolean
  onChanged: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const [picked, setPicked] = useState<Picked>()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const run = async (call: () => Promise<{ data: MemoView }>) => {
    setBusy(true)
    try {
      onChanged((await call()).data)
      return true
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
      return false
    } finally {
      setBusy(false)
    }
  }

  const declare = async () => {
    if (!picked) return
    if (!reason.trim()) {
      setError(intl.formatMessage(mm.interestReasonRequired))
      return
    }
    setError(undefined)
    const done = await run(() =>
      declareMemoInterest({
        path: { id: memo.id },
        body: { userId: picked.id, reason: reason.trim() },
        throwOnError: true,
      }),
    )
    if (done) {
      setPicked(undefined)
      setReason('')
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {memo.interests.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.interestsNone)}</p>
      ) : (
        <ul className="flex flex-col divide-y rounded-md border">
          {memo.interests.map((person) => (
            <li key={person.id} className="flex items-center gap-2 px-3 py-2 text-sm">
              <span className="flex-1">{person.name}</span>
              {editable ? (
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={busy}
                  aria-label={intl.formatMessage(mmv.routeRemove, { name: person.name })}
                  onClick={() =>
                    void run(() => removeMemoInterest({ path: { id: memo.id, userId: person.id }, throwOnError: true }))
                  }
                >
                  <X aria-hidden="true" />
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      )}
      {editable ? (
        <div className="flex flex-col gap-2">
          <Field label={intl.formatMessage(mm.interestPerson)}>
            {(p) => <PeoplePicker id={p.id} kind="user" value={picked} onChange={setPicked} />}
          </Field>
          <Field label={intl.formatMessage(mm.interestReason)} error={error} required>
            {(p) => (
              <Textarea
                {...p}
                rows={2}
                maxLength={REASON_MAX}
                value={reason}
                onChange={(e) => {
                  setReason(e.target.value)
                }}
              />
            )}
          </Field>
          <div>
            <Button variant="outline" disabled={!picked || busy} onClick={() => void declare()}>
              {intl.formatMessage(mm.interestAdd)}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
