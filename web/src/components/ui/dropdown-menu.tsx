// shadcn/ui DropdownMenu subset on Radix (copied source, owned by us). Keyboard: Enter/Space opens, arrows move, Esc closes.
import { DropdownMenu as DropdownMenuPrimitive } from 'radix-ui'
import type * as React from 'react'
import { cn } from '@/lib/utils'

/**
 * Menus are non-modal (WAI-ARIA menu button pattern). Modal Radix menus lock scrolling by injecting an inline <style>
 * element, which our strict CSP blocks, and set aria-hidden on the app root (axe: aria-hidden-focus).
 * Modal dialogs (Phase 1a+) need a CSP nonce strategy, to be decided by ADR.
 */
export function DropdownMenu(props: Omit<React.ComponentProps<typeof DropdownMenuPrimitive.Root>, 'modal'>) {
  return <DropdownMenuPrimitive.Root modal={false} {...props} />
}
export const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger
export const DropdownMenuRadioGroup = DropdownMenuPrimitive.RadioGroup

export function DropdownMenuContent({
  className,
  sideOffset = 4,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Content>) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        sideOffset={sideOffset}
        className={cn(
          'z-50 min-w-40 overflow-hidden rounded-md border bg-card p-1 text-card-foreground shadow-md',
          'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          className,
        )}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  )
}

export function DropdownMenuLabel({ className, ...props }: React.ComponentProps<typeof DropdownMenuPrimitive.Label>) {
  return <DropdownMenuPrimitive.Label className={cn('px-2 py-1.5 text-sm font-semibold', className)} {...props} />
}

export function DropdownMenuRadioItem({
  className,
  children,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.RadioItem>) {
  return (
    <DropdownMenuPrimitive.RadioItem
      className={cn(
        'relative flex min-h-11 cursor-default select-none items-center gap-2 rounded-sm py-2 ps-8 pe-2 text-sm outline-none',
        'focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
        className,
      )}
      {...props}
    >
      <span className="absolute start-2 flex size-4 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator>
          <span aria-hidden="true" className="size-2 rounded-full bg-current" />
        </DropdownMenuPrimitive.ItemIndicator>
      </span>
      {children}
    </DropdownMenuPrimitive.RadioItem>
  )
}

export function DropdownMenuItem({ className, ...props }: React.ComponentProps<typeof DropdownMenuPrimitive.Item>) {
  return (
    <DropdownMenuPrimitive.Item
      className={cn(
        'relative flex min-h-11 cursor-default select-none items-center gap-2 rounded-sm px-2 py-2 text-sm outline-none',
        'focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
        '[&_svg]:size-4 [&_svg]:shrink-0',
        className,
      )}
      {...props}
    />
  )
}

export function DropdownMenuSeparator({
  className,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Separator>) {
  return <DropdownMenuPrimitive.Separator className={cn('-mx-1 my-1 h-px bg-border', className)} {...props} />
}
