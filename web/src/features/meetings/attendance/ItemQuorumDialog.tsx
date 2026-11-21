import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMeetingAgendaOptions,
  getMeetingAttendanceOptions,
  getMeetingQuorumOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { LiveQuorumResponse, MeetingResponse, QuorumRule, QuorumSeatResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { am, amv } from './messages'
import { useQuorumPolicy } from './policy'
import {
  agendaItems,
  itemTitle,
  roleWords,
  seatCounts,
  seatInBasis,
  stateWords,
  unmetWords,
  type AgendaItem,
  type Context,
  type Person,
} from './words'

/**
 * The quorum of one item (flow Y2), for the officers: what is needed, who is counted (an alternate "for the number of
 * members only"), who is not and why (declared an interest, stepped out, apologies), and the result with the conditions
 * not met. An officer recused from the item gets the numbers only. The item in progress is shown first; any published
 * item can be chosen.
 */
export function ItemQuorumDialog({
  meeting: m,
  itemId,
  onClose,
}: {
  meeting: MeetingResponse
  itemId?: string
  onClose: () => void
}) {
  const intl = useIntl()
  const path = { path: { meetingId: m.id } }
  const agenda = useQuery(getMeetingAgendaOptions(path))
  const items = agendaItems(agenda.data)
  const [chosen, setChosen] = useState(itemId)
  const current = chosen ?? items[0]?.id
  const item = items.find((i) => i.id === current)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(am.byItemTitle)}
      className="w-[min(40rem,calc(100vw-2rem))]"
    >
      <div className="flex flex-col gap-4">
        {agenda.isPending ? (
          <LoadingState rows={2} />
        ) : items.length === 0 ? (
          <p>{intl.formatMessage(am.noItems)}</p>
        ) : (
          <>
            <Field label={intl.formatMessage(am.byItemChoose)}>
              {(p) => (
                <Select
                  {...p}
                  className="min-h-11"
                  value={current ?? ''}
                  onChange={(e) => {
                    setChosen(e.target.value)
                  }}
                >
                  {items.map((i) => (
                    <option key={i.id} value={i.id}>
                      {itemTitle(intl, i)}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
            {item ? <ItemQuorum meeting={m} item={item} items={items} /> : null}
          </>
        )}
      </div>
    </Dialog>
  )
}

function ItemQuorum({ meeting: m, item, items }: { meeting: MeetingResponse; item: AgendaItem; items: AgendaItem[] }) {
  const intl = useIntl()
  const path = { path: { meetingId: m.id } }
  const quorum = useQuery(getMeetingQuorumOptions({ ...path, query: { itemId: item.id } }))
  const register = useQuery(getMeetingAttendanceOptions(path))
  const { labels, rule } = useQuorumPolicy(m)
  if (quorum.isPending) return <LoadingState rows={3} />
  if (quorum.isError) return <ErrorState onRetry={() => void quorum.refetch()} />
  const q = quorum.data
  const ctx: Context = { items, timeZone: m.timeZone, events: register.data?.events ?? [] }
  return (
    <section aria-label={itemTitle(intl, item)} className="flex flex-col gap-3">
      <h3 className="text-lg font-semibold">
        {intl.formatMessage(amv.byItemHeading, { item: itemTitle(intl, item) })}
      </h3>
      <p>{intl.formatMessage(amv.needed, { needed: q.needed, basis: q.basis })}</p>
      {q.seats ? (
        <Seats quorum={q} seats={q.seats} rule={rule} people={register.data?.people ?? []} ctx={ctx} />
      ) : (
        <p>{intl.formatMessage(amv.countsOnly, { present: q.present, needed: q.needed })}</p>
      )}
      <Result quorum={q} labels={labels} />
    </section>
  )
}

/** The seats counted and not counted, with who fills each and why one does not count. */
function Seats({
  quorum,
  seats,
  rule,
  people,
  ctx,
}: {
  quorum: LiveQuorumResponse
  seats: QuorumSeatResponse[]
  rule: QuorumRule | undefined
  people: Person[]
  ctx: Context
}) {
  const intl = useIntl()
  const inBasis = seats.filter((s) => seatInBasis(s, rule))
  const counted = inBasis.filter((s) => seatCounts(s, rule))
  const notCounted = inBasis.filter((s) => !seatCounts(s, rule))
  const byAlternate = counted.some((s) => s.byAlternate)

  const countedName = (s: QuorumSeatResponse) => {
    const name = s.filledBy?.name ?? s.holder.name
    if (s.byAlternate) return intl.formatMessage(amv.alternateForSeat, { name, member: s.holder.name })
    const notes = [
      ...(s.role === 'MEMBER' ? [] : [roleWords(intl, s.role).toLocaleLowerCase(intl.locale)]),
      ...(s.remote ? [intl.formatMessage(am.online)] : []),
    ]
    return notes.length ? intl.formatMessage(amv.nameNotes, { name, notes: intl.formatList(notes) }) : name
  }

  const why = (s: QuorumSeatResponse) => {
    if (s.recused) return intl.formatMessage(am.whyDeclared)
    if (s.steppedOut) return intl.formatMessage(am.whySteppedOut)
    if (s.filledBy && s.remote) return intl.formatMessage(am.whyOnline)
    if (s.filledBy && s.byAlternate) return intl.formatMessage(am.whyAlternate)
    const person = people.find((p) => p.userId === s.holder.userId)
    return person ? stateWords(intl, person, ctx).toLocaleLowerCase(intl.locale) : intl.formatMessage(am.whyAway)
  }

  return (
    <div className="flex flex-col gap-2">
      <p>
        {intl.formatMessage(amv.countedLine, {
          count: counted.length,
          names: counted.length ? intl.formatList(counted.map(countedName)) : intl.formatMessage(am.nobody),
        })}
      </p>
      {byAlternate ? <p className="text-muted-foreground">{intl.formatMessage(am.alternatesNumberOnly)}</p> : null}
      {notCounted.length ? (
        <div>
          <p className="font-medium">{intl.formatMessage(am.notCountedHeading)}</p>
          <ul className="list-disc ps-5">
            {notCounted.map((s) => (
              <li key={s.memberId}>{intl.formatMessage(amv.notCountedLine, { name: s.holder.name, why: why(s) })}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {counted.length !== quorum.present ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(am.serverCounts)}</p>
      ) : null}
    </div>
  )
}

function Result({ quorum: q, labels }: { quorum: LiveQuorumResponse; labels: Record<string, string> }) {
  const intl = useIntl()
  const details = q.unmet.filter((u) => u.kind !== 'COUNT').map((u) => unmetWords(intl, u, labels))
  return (
    <div className="flex flex-col gap-1 rounded-md border p-3">
      <p className="font-semibold">
        {intl.formatMessage(q.quorate ? amv.resultQuorate : amv.resultNotQuorate, {
          present: q.present,
          needed: q.needed,
        })}
      </p>
      {details.length ? (
        <ul className="list-disc ps-5">
          {details.map((d) => (
            <li key={d}>{d}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
