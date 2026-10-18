import { useBlocker } from '@tanstack/react-router'
import { CircleDot } from 'lucide-react'
import type { ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { AlertDialog } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

const messages = defineMessages({
  unsaved: { id: 'form.unsaved', defaultMessage: 'You have unsaved changes.' },
  leaveTitle: { id: 'form.leave.title', defaultMessage: 'Leave without saving?' },
  leaveDescription: { id: 'form.leave.description', defaultMessage: 'Your changes on this page will be lost.' },
  leave: { id: 'form.leave.confirm', defaultMessage: 'Leave' },
  stay: { id: 'form.leave.cancel', defaultMessage: 'Stay on this page' },
})

/** Asks before leaving a page with unsaved changes (router navigation and browser unload). */
export function UnsavedChangesGuard({ when }: { when: boolean }) {
  const intl = useIntl()
  const blocker = useBlocker({ shouldBlockFn: () => when, enableBeforeUnload: () => when, withResolver: true })
  return (
    <AlertDialog
      open={blocker.status === 'blocked'}
      onOpenChange={(open) => {
        if (!open && blocker.status === 'blocked') blocker.reset()
      }}
      title={intl.formatMessage(messages.leaveTitle)}
      description={intl.formatMessage(messages.leaveDescription)}
      confirmLabel={intl.formatMessage(messages.leave)}
      cancelLabel={intl.formatMessage(messages.stay)}
      destructive
      onConfirm={() => blocker.proceed?.()}
    />
  )
}

export function UnsavedBanner({ visible }: { visible: boolean }) {
  const intl = useIntl()
  if (!visible) return null
  return (
    <p className="inline-flex items-center gap-1.5 rounded-full bg-status-warn-bg px-2.5 py-0.5 text-xs font-medium text-status-warn-fg">
      <CircleDot aria-hidden="true" className="size-3.5" />
      {intl.formatMessage(messages.unsaved)}
    </p>
  )
}

/** Two-column field grid (one column on narrow screens) and the actions bar of a form page. */
export function FormGrid({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('grid gap-4 rounded-lg border bg-card p-5 md:grid-cols-2', className)}>{children}</div>
}

export function FormActions({ children }: { children: ReactNode }) {
  return <div className="mt-4 flex flex-wrap justify-end gap-2">{children}</div>
}
