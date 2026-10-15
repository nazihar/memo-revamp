import { CircleCheck, CircleMinus, CircleX, TriangleAlert } from 'lucide-react'
import { defineMessages, useIntl } from 'react-intl'
import { cn } from '@/lib/utils'

export type Status = 'UP' | 'DEGRADED' | 'DOWN' | 'DISABLED'

const messages = defineMessages({
  UP: { id: 'status.up', defaultMessage: 'Up' },
  DEGRADED: { id: 'status.degraded', defaultMessage: 'Degraded' },
  DOWN: { id: 'status.down', defaultMessage: 'Down' },
  DISABLED: { id: 'status.disabled', defaultMessage: 'Not configured' },
})

const styles: Record<Status, { icon: typeof CircleCheck; className: string }> = {
  UP: { icon: CircleCheck, className: 'text-status-up' },
  DEGRADED: { icon: TriangleAlert, className: 'text-status-degraded' },
  DOWN: { icon: CircleX, className: 'text-status-down' },
  DISABLED: { icon: CircleMinus, className: 'text-status-disabled' },
}

/** Status as text + icon + colour; colour is never the only signal (WCAG 1.4.1). */
export function StatusBadge({ status, className }: { status: Status; className?: string }) {
  const intl = useIntl()
  const { icon: Icon, className: tone } = styles[status]
  return (
    <span data-status={status} className={cn('inline-flex items-center gap-1.5 font-medium', tone, className)}>
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      {intl.formatMessage(messages[status])}
    </span>
  )
}
