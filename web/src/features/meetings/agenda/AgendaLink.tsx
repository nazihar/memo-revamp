import { Link } from '@tanstack/react-router'
import { ListOrdered } from 'lucide-react'
import { defineMessages, useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'

/** In the `meetings` part: the link shows on the meeting's page, which does not load the builder's part. */
const messages = defineMessages({
  open: { id: 'meetings.agendaLink.open', defaultMessage: 'Open the agenda builder' },
  hint: {
    id: 'meetings.agendaLink.hint',
    defaultMessage: 'The draft agenda, the papers ready for it, the chair’s agreement and publishing.',
  },
})

/** The way from a meeting to its agenda builder (flows O and Q), for the committee's officers. */
export function AgendaLink({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  return (
    <div className="flex flex-col gap-1">
      <div>
        <Button asChild variant="outline" className="min-h-11">
          <Link to="/meetings/$meetingId/agenda" params={{ meetingId }}>
            <ListOrdered aria-hidden="true" />
            {intl.formatMessage(messages.open)}
          </Link>
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(messages.hint)}</p>
    </div>
  )
}
