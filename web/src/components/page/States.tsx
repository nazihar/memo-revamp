import { CircleAlert, Inbox } from 'lucide-react'
import type { ReactNode } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/misc'

const messages = defineMessages<{
  loading: NoMessageValues
  errorTitle: NoMessageValues
  reference: { id: string }
  retry: NoMessageValues
}>({
  loading: { id: 'state.loading', defaultMessage: 'Loading' },
  errorTitle: { id: 'state.error.title', defaultMessage: 'Something went wrong' },
  reference: { id: 'state.error.reference', defaultMessage: 'Reference: {id}' },
  retry: { id: 'state.error.retry', defaultMessage: 'Try again' },
})

export function EmptyState({
  title,
  description,
  action,
}: {
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-2 px-4 py-12 text-center">
      <Inbox aria-hidden="true" className="size-8 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      {description ? <p className="max-w-md text-sm text-muted-foreground">{description}</p> : null}
      {action}
    </div>
  )
}

export function LoadingState({ rows = 5 }: { rows?: number }) {
  const intl = useIntl()
  return (
    <div role="status" aria-label={intl.formatMessage(messages.loading)} className="flex flex-col gap-2 p-4">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  )
}

/**
 * A failed request: a human title and the correlation id for support, never raw error details (threat model S1).
 * [title] comes from the RFC 9457 Problem Details `title`, which the API keeps free of internals.
 */
export function ErrorState({
  title,
  correlationId,
  onRetry,
}: {
  title?: string
  correlationId?: string
  onRetry?: () => void
}) {
  const intl = useIntl()
  return (
    <div role="alert" className="flex flex-col items-center gap-2 px-4 py-12 text-center">
      <CircleAlert aria-hidden="true" className="size-8 text-destructive" />
      <p className="font-medium">{title ?? intl.formatMessage(messages.errorTitle)}</p>
      {correlationId ? (
        <p className="text-xs text-muted-foreground">{intl.formatMessage(messages.reference, { id: correlationId })}</p>
      ) : null}
      {onRetry ? (
        <Button variant="outline" onClick={onRetry}>
          {intl.formatMessage(messages.retry)}
        </Button>
      ) : null}
    </div>
  )
}
