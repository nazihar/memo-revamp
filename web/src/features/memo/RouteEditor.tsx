import { ArrowDown, ArrowUp, X } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { replaceMemoRoute } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { mm, mmv, routeProblems } from './memo-messages'

interface Entry {
  id: string
  name: string
}

/**
 * The route editor (design E01 §14): the approvers in order, added with the people picker and moved with ↑ and ↓ (no
 * drag and drop, which keyboards and screen readers cannot use well). The server checks every entry again and names
 * what is wrong with each one.
 */
export function RouteEditor({
  memo,
  onSaved,
  onProblem,
}: {
  memo: MemoView
  onSaved: (memo: MemoView) => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const [entries, setEntries] = useState<Entry[]>(memo.route.map((r) => ({ id: r.approver.id, name: r.approver.name })))
  const [picked, setPicked] = useState<Picked>()
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const move = (index: number, by: number) => {
    const next = [...entries]
    const [item] = next.splice(index, 1)
    if (!item) return
    next.splice(index + by, 0, item)
    setEntries(next)
    setErrors({})
  }

  const add = () => {
    if (!picked || entries.some((e) => e.id === picked.id)) return
    setEntries([...entries, { id: picked.id, name: picked.label }])
    setPicked(undefined)
    setErrors({})
  }

  const save = async () => {
    setBusy(true)
    setErrors({})
    try {
      const saved = await replaceMemoRoute({
        path: { id: memo.id },
        headers: { 'If-Match': `"${memo.version}"` },
        body: { approvers: entries.map((e) => e.id) },
        throwOnError: true,
      })
      onSaved(saved.data)
    } catch (problem) {
      if (isProblem(problem) && problem.errors?.length) {
        setErrors(
          Object.fromEntries(
            problem.errors.map((e) => [
              e.field,
              e.code && e.code in routeProblems
                ? intl.formatMessage(routeProblems[e.code as keyof typeof routeProblems])
                : e.message,
            ]),
          ),
        )
      } else {
        onProblem(isProblem(problem) ? problem : undefined)
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex flex-col gap-3">
      {errors.route ? (
        <p role="alert" className="text-sm text-destructive">
          {errors.route}
        </p>
      ) : null}
      {entries.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.routeEmpty)}</p>
      ) : (
        <ol className="flex flex-col divide-y rounded-md border">
          {entries.map((entry, index) => {
            const error = errors[`route[${index}]`]
            return (
              <li key={entry.id} className="flex flex-wrap items-center gap-2 px-3 py-2">
                <span className="w-6 text-sm tabular-nums text-muted-foreground">{intl.formatNumber(index + 1)}</span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium">{entry.name}</span>
                  {error ? <span className="text-xs text-destructive">{error}</span> : null}
                </span>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={index === 0}
                  aria-label={intl.formatMessage(mmv.routeUp, { name: entry.name })}
                  onClick={() => {
                    move(index, -1)
                  }}
                >
                  <ArrowUp aria-hidden="true" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={index === entries.length - 1}
                  aria-label={intl.formatMessage(mmv.routeDown, { name: entry.name })}
                  onClick={() => {
                    move(index, 1)
                  }}
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mmv.routeRemove, { name: entry.name })}
                  onClick={() => {
                    setEntries(entries.filter((e) => e.id !== entry.id))
                    setErrors({})
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            )
          })}
        </ol>
      )}
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex min-w-60 flex-1 flex-col gap-1.5">
          <label htmlFor="route-picker" className="text-sm font-medium">
            {intl.formatMessage(mm.routePicker)}
          </label>
          <PeoplePicker id="route-picker" kind="user" value={picked} onChange={setPicked} />
        </div>
        <Button variant="outline" disabled={!picked} onClick={add}>
          {intl.formatMessage(mm.routeAdd)}
        </Button>
        <Button disabled={busy} onClick={() => void save()}>
          {intl.formatMessage(mm.routeSave)}
        </Button>
      </div>
    </div>
  )
}
