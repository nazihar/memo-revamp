import { useEffect, useId, useRef, type ReactNode, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { ao } from './messages'

/** The control a field's problem moves focus to: the element marked `data-field`, or the first control inside it. */
function controlOf(form: HTMLFormElement, field: string): HTMLElement | null {
  const marked = form.querySelector<HTMLElement>(`[data-field="${CSS.escape(field)}"]`)
  if (!marked) return null
  if (marked.matches('input, textarea, select')) return marked
  return marked.querySelector<HTMLElement>('input:not([type="hidden"]), textarea, select, button')
}

/**
 * An officer's dialog: the form, its refusal (role alert) and the buttons (44 px). A field with a problem takes focus,
 * which then reads its problem; [actions] replaces the one submit button when a form offers two ("Save as draft", "Save
 * and send").
 */
export function OfficerFrame({
  title,
  description,
  busy,
  submit,
  errors,
  refusal,
  onClose,
  onSubmit,
  actions,
  wide,
  destructive,
  children,
}: {
  title: string
  description?: string | undefined
  busy: boolean
  submit?: string
  errors: Record<string, string>
  refusal?: string | undefined
  onClose: () => void
  onSubmit: (e: SyntheticEvent) => void
  actions?: (formId: string) => ReactNode
  wide?: boolean
  destructive?: boolean
  children: ReactNode
}) {
  const intl = useIntl()
  const formId = useId()
  const form = useRef<HTMLFormElement>(null)
  const first = Object.keys(errors)[0]
  useEffect(() => {
    if (first && form.current) controlOf(form.current, first)?.focus()
  }, [first, errors])
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={title}
      description={description}
      className={
        wide
          ? 'max-h-[calc(100dvh-2rem)] w-[min(44rem,calc(100vw-2rem))] overflow-y-auto'
          : 'max-h-[calc(100dvh-2rem)] overflow-y-auto'
      }
      footer={
        <>
          <Button type="button" variant="outline" className="min-h-11" onClick={onClose}>
            {intl.formatMessage(ao.cancel)}
          </Button>
          {actions ? (
            actions(formId)
          ) : (
            <Button
              type="submit"
              form={formId}
              className="min-h-11"
              variant={destructive ? 'destructive' : 'default'}
              disabled={busy}
            >
              {submit}
            </Button>
          )}
        </>
      }
    >
      <form ref={form} id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        {children}
        {refusal ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {refusal}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}

/** A radio choice with a 44 px target. */
export function Choice({
  name,
  checked,
  onSelect,
  children,
}: {
  name: string
  checked: boolean
  onSelect: () => void
  children: ReactNode
}) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md border px-3 has-[:checked]:border-primary">
      <input
        type="radio"
        name={name}
        className="size-5 shrink-0 accent-primary"
        checked={checked}
        onChange={onSelect}
      />
      <span>{children}</span>
    </label>
  )
}
