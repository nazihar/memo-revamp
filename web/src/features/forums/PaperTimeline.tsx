import { useIntl } from 'react-intl'
import { cn } from '@/lib/utils'
import { paperEvents, type Submission } from './papers'
import { pm, pmv } from './papers-messages'

/** The timeline as a list: a filled dot for what happened, with its date and who did it; a hollow one for what is next. */
export function PaperTimeline({ submission, className }: { submission: Submission; className?: string }) {
  const intl = useIntl()
  return (
    <ol aria-label={intl.formatMessage(pm.timeline)} className={cn('flex flex-col gap-2 text-sm', className)}>
      {paperEvents(intl, submission).map((e) => (
        <li key={e.key} className="flex gap-2">
          <span
            aria-hidden="true"
            className={cn(
              'mt-1.5 size-2.5 shrink-0 rounded-full border-2 border-foreground',
              e.next ? 'border-muted-foreground bg-transparent' : 'bg-foreground',
            )}
          />
          <span className={cn('flex flex-col', e.next && 'text-muted-foreground')}>
            <span dir="auto">
              {e.at ? (
                <time dateTime={e.at} className="me-2 font-medium">
                  {intl.formatDate(e.at, { dateStyle: 'medium' })}
                </time>
              ) : null}
              {e.text}
            </span>
            {e.by ? (
              <span className="text-xs text-muted-foreground">{intl.formatMessage(pmv.by, { name: e.by })}</span>
            ) : null}
          </span>
        </li>
      ))}
    </ol>
  )
}
