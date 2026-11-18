import { useState, type ComponentProps } from 'react'
import { Input } from '@/components/ui/form-controls'
import { cn } from '@/lib/utils'

/**
 * A whole number that may be left empty (null). It keeps what is typed, so clearing the box to type another number
 * does not snap back, and follows the value when it changes from outside (a reload).
 */
export function NumberInput({
  value,
  onChange,
  className,
  ...props
}: Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type'> & {
  value: number | null
  onChange: (value: number | null) => void
}) {
  const [text, setText] = useState(value == null ? '' : String(value))
  const [seen, setSeen] = useState(value)
  if (value !== seen) {
    setSeen(value)
    const typed = text.trim() === '' ? null : Number(text)
    if (typed !== value) setText(value == null ? '' : String(value))
  }
  return (
    <Input
      {...props}
      type="number"
      inputMode="numeric"
      step={1}
      className={cn('w-24', className)}
      value={text}
      onChange={(e) => {
        const next = e.target.value
        setText(next)
        const parsed = next.trim() === '' ? null : Number(next)
        onChange(parsed == null || Number.isNaN(parsed) ? null : Math.trunc(parsed))
      }}
    />
  )
}
