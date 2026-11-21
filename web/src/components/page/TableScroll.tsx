import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/**
 * The box every table sits in: a table wider than the screen scrolls inside it, never the page (WCAG 1.4.10). It is
 * positioned too, so a visually hidden caption, header or label in the table (`sr-only`, absolutely positioned) is placed
 * against the box, inside its scrolling, and not against the page, where it would stretch the page sideways at 320 px
 * (400 % zoom).
 */
export function TableScroll({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div data-table-scroll="" className={cn('relative overflow-x-auto', className)} {...props} />
}
