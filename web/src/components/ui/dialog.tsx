// Modal surfaces on the native <dialog> element (ADR-0021): the browser makes the page inert, traps focus and handles
// Escape; no injected <style>, no aria-hidden on the app root, so the strict CSP stays unchanged.
import { X } from 'lucide-react'
import { useEffect, useId, useRef, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { cn } from '@/lib/utils'
import { Button } from './button'

const MODAL_OPEN_CLASS = 'modal-open'

/** Opens and closes a <dialog> with showModal(), restores focus, and reports closing (Escape, close button, backdrop). */
function useModal(open: boolean, onOpenChange: (open: boolean) => void) {
  const ref = useRef<HTMLDialogElement>(null)
  const returnFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (open && !dialog.open) {
      returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      dialog.showModal()
      document.documentElement.classList.add(MODAL_OPEN_CLASS)
    } else if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    const onClose = () => {
      if (!document.querySelector('dialog[open]')) document.documentElement.classList.remove(MODAL_OPEN_CLASS)
      returnFocus.current?.focus()
      returnFocus.current = null
      onOpenChange(false)
    }
    dialog.addEventListener('close', onClose)
    return () => {
      dialog.removeEventListener('close', onClose)
    }
  }, [onOpenChange])

  // Unmounting while open (route change) must not leave the page scroll-locked.
  useEffect(
    () => () => {
      if (!document.querySelector('dialog[open]')) document.documentElement.classList.remove(MODAL_OPEN_CLASS)
    },
    [],
  )

  return ref
}

interface DialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description?: ReactNode
  children?: ReactNode
  footer?: ReactNode
  className?: string
}

export function Dialog({ open, onOpenChange, title, description, children, footer, className }: DialogProps) {
  const intl = useIntl()
  const ref = useModal(open, onOpenChange)
  const titleId = useId()
  const descriptionId = useId()
  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      className={cn(
        'm-auto w-[min(32rem,calc(100vw-2rem))] rounded-lg border bg-card p-0 text-card-foreground shadow-xl backdrop:bg-black/50',
        className,
      )}
    >
      <div className="flex items-start gap-4 border-b px-5 py-4">
        <div className="flex-1">
          <h2 id={titleId} className="text-lg font-semibold">
            {title}
          </h2>
          {description ? (
            <p id={descriptionId} className="mt-1 text-sm text-muted-foreground">
              {description}
            </p>
          ) : null}
        </div>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage({ id: 'dialog.close', defaultMessage: 'Close' })}
          onClick={() => ref.current?.close()}
          className="-me-2 -mt-1"
        >
          <X aria-hidden="true" />
        </Button>
      </div>
      {children ? <div className="px-5 py-4">{children}</div> : null}
      {footer ? <div className="flex flex-wrap justify-end gap-2 border-t px-5 py-3">{footer}</div> : null}
    </dialog>
  )
}

/**
 * Confirmation for consequential actions. Cancel has the initial focus, so Enter never confirms a destructive action by
 * accident.
 */
export function AlertDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  destructive,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: ReactNode
  description: ReactNode
  confirmLabel: ReactNode
  cancelLabel: ReactNode
  destructive?: boolean
  onConfirm: () => void
}) {
  const ref = useModal(open, onOpenChange)
  const titleId = useId()
  const descriptionId = useId()
  return (
    <dialog
      ref={ref}
      role="alertdialog"
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      className="m-auto w-[min(28rem,calc(100vw-2rem))] rounded-lg border bg-card p-5 text-card-foreground shadow-xl backdrop:bg-black/50"
    >
      <h2 id={titleId} className="text-lg font-semibold">
        {title}
      </h2>
      <p id={descriptionId} className="mt-2 text-sm text-muted-foreground">
        {description}
      </p>
      <div className="mt-5 flex flex-wrap justify-end gap-2">
        <Button variant="outline" autoFocus onClick={() => ref.current?.close()}>
          {cancelLabel}
        </Button>
        <Button
          variant={destructive ? 'destructive' : 'default'}
          onClick={() => {
            onConfirm()
            ref.current?.close()
          }}
        >
          {confirmLabel}
        </Button>
      </div>
    </dialog>
  )
}

/** Off-canvas panel on the inline-start side (navigation on small screens). Clicking the backdrop closes it. */
export function Sheet({
  open,
  onOpenChange,
  label,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  label: string
  children: ReactNode
}) {
  const ref = useModal(open, onOpenChange)
  return (
    <dialog
      ref={ref}
      aria-label={label}
      onClick={(e) => {
        if (e.target === e.currentTarget) ref.current?.close()
      }}
      className="fixed inset-y-0 start-0 m-0 h-dvh max-h-none w-72 max-w-[85vw] border-e bg-card p-0 text-card-foreground shadow-xl backdrop:bg-black/50"
    >
      {children}
    </dialog>
  )
}
