import { useId, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

/** Props a control receives from [Field]: its id and the accessibility wiring for hint and error. */
export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
  'aria-required'?: true
}

/**
 * A labelled form field (E01 design §3): visible label, optional hint and error, wired with `aria-describedby` and
 * `aria-invalid` so screen readers announce them. The control is rendered by [children] with the props it needs.
 */
export function Field({
  label,
  hint,
  error,
  required,
  optionalLabel,
  className,
  children,
}: {
  label: ReactNode
  hint?: ReactNode
  error?: ReactNode
  required?: boolean
  /** Shown after the label of an optional field, e.g. "(optional)". */
  optionalLabel?: ReactNode
  className?: string
  children: (control: FieldControlProps) => ReactNode
}) {
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined
  return (
    <div data-slot="field" className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
        {!required && optionalLabel ? (
          <>
            {' '}
            <span className="font-normal text-muted-foreground">{optionalLabel}</span>
          </>
        ) : null}
      </label>
      {children({
        id,
        'aria-describedby': describedBy,
        'aria-invalid': error ? true : undefined,
        'aria-required': required ? true : undefined,
      })}
      {hint ? (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-xs font-medium text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  )
}
