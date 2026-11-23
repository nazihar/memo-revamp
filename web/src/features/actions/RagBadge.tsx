import { CircleCheck, CircleDashed, CircleDot, CircleX, Contrast, TriangleAlert, type LucideIcon } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { ActionRagResponse } from '@/api/generated/types.gen'
import { cn } from '@/lib/utils'
import { am } from './messages'
import { ragWord, type RagColour } from './words'

const look: Record<RagColour, { className: string; icon: LucideIcon }> = {
  RED: { className: 'bg-status-danger-bg text-status-danger-fg', icon: TriangleAlert },
  AMBER: { className: 'bg-status-warn-bg text-status-warn-fg', icon: CircleDot },
  GREEN: { className: 'bg-status-ok-bg text-status-ok-fg', icon: CircleCheck },
  BLUE: { className: 'bg-status-neutral-bg text-status-neutral-fg', icon: Contrast },
  GREY: { className: 'bg-status-neutral-bg text-status-neutral-fg', icon: CircleCheck },
  NONE: { className: 'bg-status-neutral-bg text-status-neutral-fg', icon: CircleDashed },
}

/**
 * Red, amber or green in words with its icon (design §1.5, WCAG 1.4.1): ▲ overdue or off track, ● due soon, at risk or no
 * recent update, ✓ on track, ◐ done to be verified; a cancelled action has ⨯. The colour never stands alone.
 */
export function RagBadge({
  rag,
  size = 'sm',
  className,
}: {
  rag: ActionRagResponse
  size?: 'sm' | 'lg'
  className?: string
}) {
  const intl = useIntl()
  const { className: tone, icon } = look[rag.colour]
  const Icon = rag.word === 'CANCELLED' ? CircleX : icon
  return (
    <span
      data-rag={rag.colour}
      className={cn(
        'inline-flex w-fit shrink-0 items-center gap-1.5 rounded-full font-medium',
        size === 'lg' ? 'px-3 py-1 text-base' : 'px-2.5 py-0.5 text-sm',
        tone,
        className,
      )}
    >
      <Icon aria-hidden="true" className={size === 'lg' ? 'size-5 shrink-0' : 'size-4 shrink-0'} />
      {ragWord(intl, rag.word)}
    </span>
  )
}

/** The key under every list (principle 2): each colour's icon and what it means, with this committee type's numbers. */
export function RagKey({ amberWorkingDays }: { amberWorkingDays?: number }) {
  const intl = useIntl()
  const rows: { colour: RagColour; text: string }[] = [
    { colour: 'RED', text: intl.formatMessage(am.keyRed) },
    {
      colour: 'AMBER',
      text:
        amberWorkingDays === undefined
          ? intl.formatMessage(am.keyAmberPlain)
          : intl.formatMessage(am.keyAmber, { days: amberWorkingDays }),
    },
    { colour: 'GREEN', text: intl.formatMessage(am.keyGreen) },
    { colour: 'BLUE', text: intl.formatMessage(am.keyBlue) },
  ]
  return (
    <section className="mt-4 text-sm text-muted-foreground">
      <h2 className="mb-1 font-medium text-foreground">{intl.formatMessage(am.keyTitle)}</h2>
      <ul className="flex flex-col gap-1 sm:flex-row sm:flex-wrap sm:gap-x-6">
        {rows.map((r) => {
          const Icon = look[r.colour].icon
          return (
            <li key={r.colour} className="flex items-center gap-1.5">
              <Icon aria-hidden="true" className={cn('size-4 shrink-0 rounded-full', look[r.colour].className)} />
              {r.text}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
