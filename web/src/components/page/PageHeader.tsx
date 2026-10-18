import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** The one h1 of a page, an optional description, and the page's primary actions (E01 design §3). */
export function PageHeader({
  title,
  description,
  status,
  actions,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  status?: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('mb-5 flex flex-wrap items-end gap-3', className)}>
      <div className="min-w-0 flex-[1_1_20rem]">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-[1.375rem] font-semibold leading-tight">{title}</h1>
          {status}
        </div>
        {description ? <p className="mt-1 text-sm text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
