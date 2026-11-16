import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/form-controls'
import { cn } from '@/lib/utils'
import { mm } from '@/features/memo/memo-messages'
import { imv } from './inbox-messages'

/** A row of an inbox list; rows become two-line cards on phones. */
export const ROW =
  'flex min-h-14 w-full flex-col gap-1 px-4 py-3 text-start hover:bg-accent focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ring md:flex-row md:items-center md:gap-4'

/** An inbox list with its loading, error and empty states and "Show more". */
export function Listing({
  pending,
  error,
  onRetry,
  empty,
  more,
  loadingMore,
  header,
  children,
}: {
  pending: boolean
  error: boolean
  onRetry: () => void
  empty?: string
  more?: () => void
  loadingMore: boolean
  /** Above the rows, inside the list's frame (for example "Select all shown"). */
  header?: ReactNode
  children: ReactNode
}) {
  const intl = useIntl()
  if (pending) return <LoadingState />
  if (error) return <ErrorState onRetry={onRetry} />
  if (empty) return <EmptyState title={empty} />
  return (
    <>
      <div className="rounded-md border">
        {header ? <div className="border-b">{header}</div> : null}
        <ul className="flex flex-col divide-y">{children}</ul>
      </div>
      {more ? (
        <div className="mt-4 flex justify-center">
          <Button variant="outline" disabled={loadingMore} onClick={more}>
            {intl.formatMessage(mm.loadMore)}
          </Button>
        </div>
      ) : null}
    </>
  )
}

/** A list item with a checkbox before its content; the checkbox's target is the full height of the row. */
export function SelectableItem({
  name,
  checked,
  onChange,
  children,
  className,
}: {
  /** What the checkbox selects, for its accessible name. */
  name: string
  checked: boolean
  onChange: (on: boolean) => void
  children: ReactNode
  className?: string
}) {
  const intl = useIntl()
  return (
    <li className={cn('flex items-stretch', checked && 'bg-accent/60', className)}>
      <label className="flex min-w-11 shrink-0 cursor-pointer items-center justify-center ps-2">
        <Checkbox
          aria-label={intl.formatMessage(imv.selectItem, { name })}
          checked={checked}
          onChange={(e) => {
            onChange(e.target.checked)
          }}
        />
      </label>
      <div className="min-w-0 flex-1">{children}</div>
    </li>
  )
}
