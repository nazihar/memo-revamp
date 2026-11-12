import { ArrowDown, ArrowUp, X } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { replaceMemoRoute } from '@/api/generated/sdk.gen'
import type { MemoView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { mm, mmv, routeProblems } from './memo-messages'
import { fromView, itemKey, itemLabel, toRequest, type RouteItem, type RouteMember } from './route-entry'
import { rm, rmv } from './route-messages'

type Kind = 'person' | 'position' | 'group'

/**
 * The route editor (design E01 §14, E04 §4.7, ux-review flow 6): the entries in order — a person, a position (whoever
 * holds it when the memo reaches it), or a group deciding together — added with the pickers and moved with ↑ and ↓ (no
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
  const [entries, setEntries] = useState<RouteItem[]>(() =>
    memo.route.flatMap((r) => {
      const item = fromView(intl, r)
      return item ? [item] : []
    }),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)

  const change = (next: RouteItem[]) => {
    setEntries(next)
    setErrors({})
  }

  const move = (index: number, by: number) => {
    const next = [...entries]
    const [item] = next.splice(index, 1)
    if (!item) return
    next.splice(index + by, 0, item)
    change(next)
  }

  const add = (item: RouteItem) => {
    if (entries.some((e) => itemKey(e) === itemKey(item))) return
    change([...entries, item])
  }

  const save = async () => {
    setBusy(true)
    setErrors({})
    try {
      const saved = await replaceMemoRoute({
        path: { id: memo.id },
        headers: { 'If-Match': `"${memo.version}"` },
        body: { entries: entries.map(toRequest) },
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
            const name = itemLabel(intl, entry)
            return (
              <li key={itemKey(entry)} className="flex flex-wrap items-center gap-2 px-3 py-2">
                <span className="w-6 text-sm tabular-nums text-muted-foreground">{intl.formatNumber(index + 1)}</span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="text-sm font-medium break-words">{name}</span>
                  {error ? <span className="text-xs text-destructive">{error}</span> : null}
                </span>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={index === 0}
                  aria-label={intl.formatMessage(mmv.routeUp, { name })}
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
                  aria-label={intl.formatMessage(mmv.routeDown, { name })}
                  onClick={() => {
                    move(index, 1)
                  }}
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mmv.routeRemove, { name })}
                  onClick={() => {
                    change(entries.filter((_, i) => i !== index))
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            )
          })}
        </ol>
      )}
      <AddEntry onAdd={add} />
      <div>
        <Button disabled={busy} onClick={() => void save()}>
          {intl.formatMessage(mm.routeSave)}
        </Button>
      </div>
    </div>
  )
}

/** Adds a person, a position, or a group of two or more of them. */
function AddEntry({ onAdd }: { onAdd: (item: RouteItem) => void }) {
  const intl = useIntl()
  const ids = useId()
  const [kind, setKind] = useState<Kind>('person')
  const [picked, setPicked] = useState<Picked>()
  const [mode, setMode] = useState<'ALL' | 'ANY'>('ALL')
  const [memberKind, setMemberKind] = useState<RouteMember['kind']>('person')
  const [members, setMembers] = useState<RouteMember[]>([])

  const pickedMember = (as: RouteMember['kind']): RouteMember | undefined =>
    picked
      ? { kind: as, id: picked.id, name: picked.detail ? `${picked.label} (${picked.detail})` : picked.label }
      : undefined

  const addSingle = () => {
    const item = pickedMember(kind === 'position' ? 'position' : 'person')
    if (!item) return
    onAdd(item)
    setPicked(undefined)
  }

  const addMember = () => {
    const item = pickedMember(memberKind)
    if (!item || members.some((m) => m.kind === item.kind && m.id === item.id)) return
    setMembers([...members, item])
    setPicked(undefined)
  }

  const addGroup = () => {
    if (members.length < 2) return
    onAdd({ kind: 'group', mode, members })
    setMembers([])
  }

  const pickerKind = kind === 'group' ? memberKind : kind
  return (
    <div className="flex flex-col gap-3 rounded-md border p-3">
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex flex-col gap-1.5">
          <label htmlFor={`${ids}-kind`} className="text-sm font-medium">
            {intl.formatMessage(rm.kind)}
          </label>
          <Select
            id={`${ids}-kind`}
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as Kind)
              setPicked(undefined)
            }}
          >
            <option value="person">{intl.formatMessage(rm.person)}</option>
            <option value="position">{intl.formatMessage(rm.position)}</option>
            <option value="group">{intl.formatMessage(rm.group)}</option>
          </Select>
        </div>
        {kind === 'group' ? (
          <>
            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${ids}-mode`} className="text-sm font-medium">
                {intl.formatMessage(rm.mode)}
              </label>
              <Select
                id={`${ids}-mode`}
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value as 'ALL' | 'ANY')
                }}
              >
                <option value="ALL">{intl.formatMessage(rm.all)}</option>
                <option value="ANY">{intl.formatMessage(rm.any)}</option>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor={`${ids}-member-kind`} className="text-sm font-medium">
                {intl.formatMessage(rm.memberKind)}
              </label>
              <Select
                id={`${ids}-member-kind`}
                value={memberKind}
                onChange={(e) => {
                  setMemberKind(e.target.value as RouteMember['kind'])
                  setPicked(undefined)
                }}
              >
                <option value="person">{intl.formatMessage(rm.person)}</option>
                <option value="position">{intl.formatMessage(rm.position)}</option>
              </Select>
            </div>
          </>
        ) : null}
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex min-w-60 flex-1 flex-col gap-1.5">
          <label htmlFor={`${ids}-picker`} className="text-sm font-medium">
            {intl.formatMessage(
              kind === 'group' ? rm.member : pickerKind === 'position' ? rm.positionPicker : mm.routePicker,
            )}
          </label>
          <PeoplePicker
            key={pickerKind}
            id={`${ids}-picker`}
            kind={pickerKind === 'position' ? 'position' : 'user'}
            value={picked}
            onChange={setPicked}
            describedBy={pickerKind === 'position' ? `${ids}-position-hint` : undefined}
          />
          {pickerKind === 'position' ? (
            <p id={`${ids}-position-hint`} className="text-xs text-muted-foreground">
              {intl.formatMessage(rm.positionHint)}
            </p>
          ) : null}
        </div>
        {kind === 'group' ? (
          <Button variant="outline" disabled={!picked} onClick={addMember}>
            {intl.formatMessage(rm.addMember)}
          </Button>
        ) : (
          <Button variant="outline" disabled={!picked} onClick={addSingle}>
            {intl.formatMessage(mm.routeAdd)}
          </Button>
        )}
      </div>
      {kind === 'group' ? (
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">{intl.formatMessage(rm.members)}</span>
          {members.length === 0 ? (
            <p className="text-xs text-muted-foreground">{intl.formatMessage(rm.groupNeeds)}</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {members.map((m) => (
                <li key={`${m.kind}:${m.id}`} className="flex items-center gap-1 rounded-md border px-2 py-1 text-sm">
                  {m.name}
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={intl.formatMessage(rmv.removeMember, { name: m.name })}
                    onClick={() => {
                      setMembers(members.filter((x) => x !== m))
                    }}
                  >
                    <X aria-hidden="true" />
                  </Button>
                </li>
              ))}
            </ul>
          )}
          <div>
            <Button variant="outline" disabled={members.length < 2} onClick={addGroup}>
              {intl.formatMessage(rm.addGroup)}
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
