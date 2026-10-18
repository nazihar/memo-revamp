import { ChevronRight } from 'lucide-react'
import { Tabs as TabsPrimitive } from 'radix-ui'
import type * as React from 'react'
import { useIntl } from 'react-intl'
import { cn } from '@/lib/utils'

// Tabs: Radix positions nothing and injects no styles (CSP-safe, ADR-0021). Arrow keys move between tabs.
export const Tabs = TabsPrimitive.Root

export function TabsList({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.List>) {
  return <TabsPrimitive.List className={cn('flex gap-1 border-b', className)} {...props} />
}

export function TabsTrigger({ className, ...props }: React.ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        '-mb-px min-h-10 border-b-2 border-transparent px-3 text-sm text-muted-foreground hover:text-foreground',
        'data-[state=active]:border-primary data-[state=active]:font-semibold data-[state=active]:text-accent-foreground',
        className,
      )}
      {...props}
    />
  )
}

export const TabsContent = TabsPrimitive.Content

export interface Crumb {
  label: string
  /** Rendered as a link when present; the last crumb is the current page. */
  href?: string
}

export function Breadcrumbs({ items, className }: { items: Crumb[]; className?: string }) {
  const intl = useIntl()
  if (items.length === 0) return null
  return (
    <nav
      aria-label={intl.formatMessage({ id: 'shell.breadcrumb', defaultMessage: 'Breadcrumb' })}
      className={className}
    >
      <ol className="flex flex-wrap items-center gap-1.5 text-sm text-muted-foreground">
        {items.map((item, i) => {
          const last = i === items.length - 1
          return (
            <li key={`${String(i)}-${item.label}`} className="flex items-center gap-1.5">
              {last ? (
                <span aria-current="page" className="font-medium text-foreground">
                  {item.label}
                </span>
              ) : item.href ? (
                <a href={item.href} className="hover:text-foreground hover:underline">
                  {item.label}
                </a>
              ) : (
                <span>{item.label}</span>
              )}
              {last ? null : <ChevronRight aria-hidden="true" className="size-3.5 rtl:rotate-180" />}
            </li>
          )
        })}
      </ol>
    </nav>
  )
}

/** Placeholder block while content loads; hidden from assistive technology (the surrounding region says "Loading"). */
export function Skeleton({ className }: { className?: string }) {
  return <div aria-hidden="true" className={cn('animate-pulse rounded-md bg-muted', className)} />
}
