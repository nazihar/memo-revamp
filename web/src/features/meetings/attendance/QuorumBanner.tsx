import { useQuery } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getMeetingAgendaOptions,
  getMeetingAttendanceOptions,
  getMeetingQuorumChangesOptions,
  getMeetingQuorumOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { LiveQuorumResponse, MeetingResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ItemQuorumDialog } from './ItemQuorumDialog'
import { am, amv } from './messages'
import { useQuorumPolicy } from './policy'
import { agendaItems, clock, itemStart, itemWords, LIVE_REFRESH_MS, unmetWords } from './words'

/**
 * The live quorum (flow Y1) for the officers of a meeting in progress, above the agenda and the register: the meeting's
 * quorum and the item in progress's in words ("Quorate — meeting: 5 of 7 present, 4 needed."), why an item is not
 * quorate, and since when the meeting is not; refreshed every 15 seconds. A change between quorate and not is announced
 * politely ("Quorum lost …", "Quorum restored …"); the state is always in words, with an icon, never in colour alone.
 * "Quorum by item…" opens the per-item view (Y2).
 */
export function QuorumBanner({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const titleId = useId()
  const path = { path: { meetingId: m.id } }
  const register = useQuery({ ...getMeetingAttendanceOptions(path), refetchInterval: LIVE_REFRESH_MS })
  const itemId = register.data?.itemInProgress?.itemId
  const item = useQuery({
    ...getMeetingQuorumOptions({ ...path, query: { itemId: itemId ?? '' } }),
    enabled: itemId != null,
    refetchInterval: LIVE_REFRESH_MS,
  })
  const changes = useQuery({ ...getMeetingQuorumChangesOptions(path), refetchInterval: LIVE_REFRESH_MS })
  const agenda = useQuery(getMeetingAgendaOptions(path))
  const { labels, rule } = useQuorumPolicy(m)
  const [opened, setOpened] = useState(false)
  // The polite announcement of a change, set only when the state changes after the banner first showed it.
  const [seen, setSeen] = useState<boolean>()
  const [announcement, setAnnouncement] = useState('')

  const quorum = register.data?.quorum
  if (quorum && quorum.quorate !== seen) {
    if (seen !== undefined) {
      const numbers = { present: quorum.present, basis: quorum.basis, needed: quorum.needed }
      setAnnouncement(intl.formatMessage(quorum.quorate ? amv.announceRestored : amv.announceLost, numbers))
    }
    setSeen(quorum.quorate)
  }

  const items = agendaItems(agenda.data)
  const lost = quorum && !quorum.quorate ? changes.data?.findLast((c) => c.level === 'MEETING') : undefined
  const since = lost?.kind === 'LOST' ? lost : undefined
  const itemName = itemId ? itemStart(intl, items, itemId) : undefined

  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'flex flex-col gap-2 rounded-md border p-4 print:hidden',
        quorum && !quorum.quorate ? 'border-destructive/50 bg-status-danger-bg' : 'bg-card',
      )}
    >
      <h2 id={titleId} className="sr-only">
        {intl.formatMessage(am.quorumTitle)}
      </h2>
      <div role="status" aria-live="polite" className="sr-only">
        {announcement}
      </div>
      {register.isPending ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.quorumLoading)}</p>
      ) : !quorum ? (
        <p className="text-muted-foreground">{intl.formatMessage(am.quorumUnavailable)}</p>
      ) : (
        <>
          <p className="flex items-start gap-2 text-lg font-semibold">
            {quorum.quorate ? (
              <Check aria-hidden="true" className="mt-1 size-5 shrink-0 text-status-ok-fg" />
            ) : (
              <X aria-hidden="true" className="mt-1 size-5 shrink-0 text-status-danger-fg" />
            )}
            <span>
              {since?.itemId
                ? intl.formatMessage(amv.lostSinceDuring, {
                    at: clock(intl, since.at, m.timeZone),
                    item: itemWords(intl, items, since.itemId),
                    present: quorum.present,
                    basis: quorum.basis,
                    needed: quorum.needed,
                  })
                : since
                  ? intl.formatMessage(amv.lostSince, {
                      at: clock(intl, since.at, m.timeZone),
                      present: quorum.present,
                      basis: quorum.basis,
                      needed: quorum.needed,
                    })
                  : intl.formatMessage(quorum.quorate ? amv.meetingQuorate : amv.meetingWaiting, {
                      present: quorum.present,
                      basis: quorum.basis,
                      needed: quorum.needed,
                    })}
            </span>
          </p>
          {!quorum.quorate ? (
            <>
              <Unmet quorum={quorum} labels={labels} />
              <p>{intl.formatMessage(am.noDecision)}</p>
            </>
          ) : null}
          {itemName && item.data ? (
            <ItemLine quorum={item.data} name={itemName} recusedCount={rule?.recusedCount ?? false} />
          ) : null}
        </>
      )}
      <div>
        <Button
          variant="outline"
          className="min-h-11"
          onClick={() => {
            setOpened(true)
          }}
        >
          {intl.formatMessage(am.byItemOpen)}
        </Button>
      </div>
      {opened ? (
        <ItemQuorumDialog
          meeting={m}
          {...(itemId ? { itemId } : {})}
          onClose={() => {
            setOpened(false)
          }}
        />
      ) : null}
    </section>
  )
}

/** The conditions of the rule not met, one line each. */
function Unmet({ quorum, labels }: { quorum: LiveQuorumResponse; labels: Record<string, string> }) {
  const intl = useIntl()
  const lines = quorum.unmet.filter((u) => u.kind !== 'COUNT')
  if (!lines.length) return null
  return (
    <ul className="list-disc ps-5">
      {lines.map((u, i) => (
        <li key={`${u.kind ?? ''}-${String(i)}`}>{unmetWords(intl, u, labels)}</li>
      ))}
    </ul>
  )
}

/**
 * The item in progress: "Item 5: 3 of 7 counted, 4 needed — not quorate", and why ("2 members have declared an interest
 * and do not count for it"); the counts only for an officer recused from the item (no seats).
 */
function ItemLine({ quorum, name, recusedCount }: { quorum: LiveQuorumResponse; name: string; recusedCount: boolean }) {
  const intl = useIntl()
  const numbers = { item: name, present: quorum.present, basis: quorum.basis, needed: quorum.needed }
  // Members in the room who declared an interest, not counted for the item (with the rule's `recusedCount` off).
  const declared = recusedCount ? 0 : (quorum.seats ?? []).filter((s) => s.recused && s.filledBy != null).length
  return (
    <div className="flex flex-col gap-1 border-t pt-2">
      <p className="flex items-start gap-2 font-medium">
        {quorum.quorate ? (
          <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-ok-fg" />
        ) : (
          <X aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-status-danger-fg" />
        )}
        <span>{intl.formatMessage(quorum.quorate ? amv.itemQuorate : amv.itemNotQuorate, numbers)}</span>
      </p>
      {!quorum.quorate && declared > 0 ? (
        <p>{intl.formatMessage(amv.itemDeclared, { item: name, count: declared })}</p>
      ) : null}
    </div>
  )
}
