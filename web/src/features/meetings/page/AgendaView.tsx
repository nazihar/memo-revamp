import { Check, FileText } from 'lucide-react'
import { useId } from 'react'
import { useIntl } from 'react-intl'
import type {
  MemberAgendaResponse,
  MemberItemResponse,
  MemberPaperResponse,
  MemberSectionResponse,
} from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { pm, pmv } from './messages'
import { itemChanges, lineName, paperChanges, paperLabel } from './words'

/** What the reader can do from the agenda; absent in a read-only rendering (a preview). */
export interface AgendaActions {
  onOpenPaper?: (paper: MemberPaperResponse, item: MemberItemResponse) => void
  onAsk?: (item: MemberItemResponse) => void
  onWithdraw?: (item: MemberItemResponse) => void
  /** The item whose request is being sent or withdrawn. */
  busyItem?: string
}

/**
 * The agenda as one reader may see it (flow P1, design §1.9): sections with their items, each line in full, by title
 * only ("— papers restricted") or as "Restricted item"; times on the meeting's clock, purposes in words, papers as
 * large buttons with "Opened" ticks, and "Ask to discuss" on consent items. Print shows numbers, times and purposes.
 */
export function AgendaView({
  agenda,
  latest,
  actions = {},
}: {
  agenda: MemberAgendaResponse
  latest: boolean
  actions?: AgendaActions
}) {
  const intl = useIntl()
  const sections = agenda.sections.filter((s) => s.items.length)
  if (!sections.length) return <p className="text-lg">{intl.formatMessage(pm.nothingShown)}</p>
  return (
    <div className="flex flex-col gap-6">
      {sections.map((section) => (
        <Section key={section.id} agenda={agenda} section={section} latest={latest} actions={actions} />
      ))}
    </div>
  )
}

function Section({
  agenda,
  section,
  latest,
  actions,
}: {
  agenda: MemberAgendaResponse
  section: MemberSectionResponse
  latest: boolean
  actions: AgendaActions
}) {
  const intl = useIntl()
  const headingId = useId()
  const items = (
    <ul className="flex flex-col divide-y border-y">
      {section.items.map((item) => (
        <Item key={item.id} agenda={agenda} item={item} latest={latest} actions={actions} />
      ))}
    </ul>
  )
  // A members-only section shown to someone outside the membership has no heading (ADR-0061 amendment).
  if (!section.title) return <div className="flex flex-col gap-2">{items}</div>
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-2 break-inside-avoid-page">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <h3 id={headingId} className="text-xl font-semibold">
          {section.title}
        </h3>
        {section.membersOnly ? <Badge tone="neutral">{intl.formatMessage(pm.membersOnly)}</Badge> : null}
      </div>
      {section.kind === 'CONSENT' ? (
        <p className="text-muted-foreground">{intl.formatMessage(pm.consentHint)}</p>
      ) : null}
      {items}
    </section>
  )
}

function Item({
  agenda,
  item,
  latest,
  actions,
  sub = false,
}: {
  agenda: MemberAgendaResponse
  item: MemberItemResponse
  latest: boolean
  actions: AgendaActions
  sub?: boolean
}) {
  const intl = useIntl()
  const name = lineName(intl, item)
  const full = item.access === 'FULL'
  const time = item.startsAt
    ? intl.formatTime(item.startsAt, { hour: 'numeric', minute: '2-digit', timeZone: agenda.timeZone })
    : undefined
  const changes = itemChanges(intl, item.changes, item.previousNumber)
  const facts = [
    time,
    item.kind === 'BREAK' && item.minutes ? intl.formatMessage(pmv.minutes, { minutes: item.minutes }) : undefined,
    item.purpose && item.kind !== 'BREAK' ? intl.formatMessage(pm[item.purpose]) : undefined,
  ].filter((f): f is string => Boolean(f))
  return (
    <li className={cn('flex flex-col gap-2 py-3 break-inside-avoid', sub && 'ps-6 sm:ps-10')}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h4 className={cn('font-semibold', sub ? 'text-lg' : 'text-xl')}>
          {name}
          {item.access === 'TITLE' ? (
            <>
              {' '}
              <span className="font-normal text-muted-foreground">{intl.formatMessage(pm.papersRestricted)}</span>
            </>
          ) : null}
        </h4>
        {facts.length ? <p className="text-lg tabular-nums text-muted-foreground">{facts.join(' · ')}</p> : null}
      </div>
      {full ? <Details item={item} name={name} latest={latest} agenda={agenda} actions={actions} /> : null}
      {changes ? (
        <p className="w-fit rounded-md bg-status-warn-bg px-2 py-0.5 text-status-warn-fg print:hidden">
          {intl.formatMessage(pmv.changed, { changes })}
        </p>
      ) : null}
      {item.subItems.length ? (
        <ul className="flex flex-col divide-y">
          {item.subItems.map((s) => (
            <Item key={s.id} agenda={agenda} item={s} latest={latest} actions={actions} sub />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

/** What a reader who sees the item in full reads under its line. */
function Details({
  agenda,
  item,
  name,
  latest,
  actions,
}: {
  agenda: MemberAgendaResponse
  item: MemberItemResponse
  name: string
  latest: boolean
  actions: AgendaActions
}) {
  const intl = useIntl()
  const presenters = item.presenters.map((p) => p.name).filter(Boolean)
  const lines = [
    presenters.length
      ? intl.formatMessage(pmv.presenters, {
          count: presenters.length,
          names: intl.formatList(presenters, { type: 'conjunction' }),
        })
      : undefined,
    item.late !== 'NONE' ? intl.formatMessage(pm[item.late]) : undefined,
  ].filter((l): l is string => Boolean(l))
  const discussion = item.discussion
  const asking = actions.busyItem === item.id
  return (
    <div className="flex flex-col gap-2 text-lg">
      {lines.length ? <p>{lines.join(' · ')}</p> : null}
      {item.resolutionSought ? <p>{item.resolutionSought}</p> : null}
      {item.description ? <p className="whitespace-pre-line text-muted-foreground">{item.description}</p> : null}
      {discussion?.requestedBy.length ? (
        <p className="font-medium">
          {intl.formatMessage(pmv.liftedBy, {
            names: intl.formatList(
              discussion.requestedBy.map((p) => p.name),
              { type: 'conjunction' },
            ),
          })}
        </p>
      ) : null}
      {item.papers.length ? (
        <ul aria-label={intl.formatMessage(pmv.papersOf, { item: name })} className="flex flex-col gap-2 print:hidden">
          {item.papers.map((paper) => (
            <Paper key={paper.id} paper={paper} item={item} actions={actions} />
          ))}
        </ul>
      ) : null}
      {latest && discussion && agenda.status === 'SCHEDULED' && (actions.onAsk || actions.onWithdraw) ? (
        <div className="flex flex-wrap gap-2 print:hidden">
          {discussion.canAsk && actions.onAsk ? (
            <Button
              variant="outline"
              className="min-h-11 text-base"
              disabled={asking}
              aria-label={intl.formatMessage(pmv.askNamed, { item: name })}
              onClick={() => actions.onAsk?.(item)}
            >
              {intl.formatMessage(pm.askItem)}
            </Button>
          ) : null}
          {discussion.mine && actions.onWithdraw ? (
            <Button
              variant="outline"
              className="min-h-11 text-base"
              disabled={asking}
              aria-label={intl.formatMessage(pmv.withdrawNamed, { item: name })}
              onClick={() => actions.onWithdraw?.(item)}
            >
              {intl.formatMessage(pm.withdraw)}
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

function Paper({
  paper,
  item,
  actions,
}: {
  paper: MemberPaperResponse
  item: MemberItemResponse
  actions: AgendaActions
}) {
  const intl = useIntl()
  const changes = paperChanges(
    intl,
    paper.changes.filter((f) => f !== 'LATE' && f !== 'TABLED'),
  )
  const late = paper.late !== 'NONE' && item.late === 'NONE' ? intl.formatMessage(pm[paper.late]) : undefined
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <Button
        variant="outline"
        className="h-auto min-h-11 max-w-full justify-start whitespace-normal py-2 text-start text-base"
        data-paper-id={paper.id}
        disabled={!actions.onOpenPaper}
        onClick={() => actions.onOpenPaper?.(paper, item)}
      >
        <FileText aria-hidden="true" />
        {paperLabel(intl, paper)}
      </Button>
      {paper.opened ? (
        <span className="inline-flex items-center gap-1 text-status-ok-fg">
          <Check aria-hidden="true" className="size-5" />
          {intl.formatMessage(pm.opened)}
        </span>
      ) : null}
      {late ? <span className="text-muted-foreground">{late}</span> : null}
      {changes ? <span className="text-muted-foreground">{intl.formatMessage(pmv.changed, { changes })}</span> : null}
      {!paper.ready ? (
        <span className="w-full text-muted-foreground">{intl.formatMessage(pm.notReadyHint)}</span>
      ) : null}
    </li>
  )
}
