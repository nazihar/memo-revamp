// Native form controls styled for Direction A (40px). Native elements keep keyboard, autofill and screen-reader behaviour.
import type * as React from 'react'
import { cn } from '@/lib/utils'

const control =
  'w-full min-h-10 rounded-md border border-input bg-card px-3 text-sm text-foreground placeholder:text-muted-foreground disabled:opacity-60 aria-invalid:border-destructive'

export function Input({ className, ...props }: React.ComponentProps<'input'>) {
  return <input data-slot="input" className={cn(control, className)} {...props} />
}

export function Textarea({ className, ...props }: React.ComponentProps<'textarea'>) {
  return <textarea data-slot="textarea" className={cn(control, 'py-2 leading-relaxed', className)} {...props} />
}

export function Select({ className, ...props }: React.ComponentProps<'select'>) {
  return <select data-slot="select" className={cn(control, 'pe-8', className)} {...props} />
}

export function Checkbox({ className, ...props }: Omit<React.ComponentProps<'input'>, 'type'>) {
  return <input type="checkbox" data-slot="checkbox" className={cn('size-4 accent-primary', className)} {...props} />
}
