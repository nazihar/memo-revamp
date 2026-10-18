import { CircleCheck, CircleDot, CircleMinus, CircleX, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export type Tone = 'ok' | 'warn' | 'danger' | 'neutral'

const tones: Record<Tone, { className: string; icon: LucideIcon }> = {
  ok: { className: 'bg-status-ok-bg text-status-ok-fg', icon: CircleCheck },
  warn: { className: 'bg-status-warn-bg text-status-warn-fg', icon: CircleDot },
  danger: { className: 'bg-status-danger-bg text-status-danger-fg', icon: CircleX },
  neutral: { className: 'bg-status-neutral-bg text-status-neutral-fg', icon: CircleMinus },
}

/** A status pill: text plus icon plus colour, never colour alone (WCAG 1.4.1). */
export function Badge({ tone, children, className }: { tone: Tone; children: ReactNode; className?: string }) {
  const { className: toneClass, icon: Icon } = tones[tone]
  return (
    <span
      data-tone={tone}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium',
        toneClass,
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      {children}
    </span>
  )
}
