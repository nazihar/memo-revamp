import { ChevronRight } from 'lucide-react'
import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { cn } from '@/lib/utils'

export interface TreeNode {
  id: string
  label: string
  children?: TreeNode[]
}

interface VisibleNode {
  node: TreeNode
  depth: number
  parentId: string | null
  expanded: boolean
  hasChildren: boolean
}

function flatten(nodes: TreeNode[], expanded: Set<string>, depth = 0, parentId: string | null = null): VisibleNode[] {
  return nodes.flatMap((node) => {
    const hasChildren = (node.children?.length ?? 0) > 0
    const isExpanded = hasChildren && expanded.has(node.id)
    const self: VisibleNode = { node, depth, parentId, expanded: isExpanded, hasChildren }
    return isExpanded ? [self, ...flatten(node.children ?? [], expanded, depth + 1, node.id)] : [self]
  })
}

/**
 * Single-select tree following the WAI-ARIA tree pattern: one tab stop (roving tabindex); Up/Down move; Right expands
 * or enters; Left collapses or goes to the parent; Home/End; Enter/Space selects; typing a letter jumps to the next
 * item starting with it. Arrow semantics follow the reading direction under RTL.
 */
export function TreePanel({
  label,
  nodes,
  selectedId,
  onSelect,
  defaultExpanded = [],
}: {
  label: string
  nodes: TreeNode[]
  selectedId?: string
  onSelect: (id: string) => void
  defaultExpanded?: string[]
}) {
  const [expanded, setExpanded] = useState(() => new Set(defaultExpanded))
  const visible = useMemo(() => flatten(nodes, expanded), [nodes, expanded])
  const [focusedId, setFocusedId] = useState<string | undefined>(selectedId ?? visible[0]?.node.id)
  const refs = useRef(new Map<string, HTMLLIElement>())

  const focus = (id: string) => {
    setFocusedId(id)
    refs.current.get(id)?.focus()
  }
  const toggle = (id: string, open: boolean) => {
    setExpanded((current) => {
      const next = new Set(current)
      if (open) next.add(id)
      else next.delete(id)
      return next
    })
  }

  const onKeyDown = (e: KeyboardEvent<HTMLUListElement>) => {
    const index = visible.findIndex((v) => v.node.id === focusedId)
    const current = visible[index]
    if (!current) return
    const rtl = getComputedStyle(e.currentTarget).direction === 'rtl'
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight'
    const back = rtl ? 'ArrowRight' : 'ArrowLeft'
    let handled = true
    if (e.key === 'ArrowDown') {
      const next = visible[index + 1]
      if (next) focus(next.node.id)
    } else if (e.key === 'ArrowUp') {
      const prev = visible[index - 1]
      if (prev) focus(prev.node.id)
    } else if (e.key === 'Home') {
      const first = visible[0]
      if (first) focus(first.node.id)
    } else if (e.key === 'End') {
      const last = visible[visible.length - 1]
      if (last) focus(last.node.id)
    } else if (e.key === forward) {
      if (current.hasChildren && !current.expanded) toggle(current.node.id, true)
      else if (current.expanded) {
        const child = visible[index + 1]
        if (child) focus(child.node.id)
      }
    } else if (e.key === back) {
      if (current.expanded) toggle(current.node.id, false)
      else if (current.parentId) focus(current.parentId)
    } else if (e.key === 'Enter' || e.key === ' ') {
      onSelect(current.node.id)
    } else if (e.key.length === 1 && /\S/.test(e.key)) {
      const ch = e.key.toLocaleLowerCase()
      const order = [...visible.slice(index + 1), ...visible.slice(0, index + 1)]
      const match = order.find((v) => v.node.label.toLocaleLowerCase().startsWith(ch))
      if (match) focus(match.node.id)
    } else {
      handled = false
    }
    if (handled) e.preventDefault()
  }

  return (
    <ul role="tree" aria-label={label} onKeyDown={onKeyDown} className="flex flex-col gap-0.5">
      {visible.map((v) => {
        const selected = v.node.id === selectedId
        return (
          <li
            key={v.node.id}
            ref={(el) => {
              if (el) refs.current.set(v.node.id, el)
              else refs.current.delete(v.node.id)
            }}
            role="treeitem"
            aria-level={v.depth + 1}
            aria-selected={selected}
            aria-expanded={v.hasChildren ? v.expanded : undefined}
            tabIndex={v.node.id === focusedId ? 0 : -1}
            onClick={() => {
              setFocusedId(v.node.id)
              if (v.hasChildren) toggle(v.node.id, !v.expanded)
              onSelect(v.node.id)
            }}
            onFocus={() => {
              setFocusedId(v.node.id)
            }}
            style={{ paddingInlineStart: `${String(0.5 + v.depth)}rem` }}
            className={cn(
              'flex min-h-9 cursor-default items-center gap-1.5 rounded-md pe-2 text-sm hover:bg-accent/60',
              selected && 'bg-accent font-semibold text-accent-foreground',
            )}
          >
            <ChevronRight
              aria-hidden="true"
              className={cn(
                'size-3.5 shrink-0 text-muted-foreground transition-transform rtl:rotate-180',
                v.expanded && 'rotate-90 rtl:rotate-90',
                !v.hasChildren && 'invisible',
              )}
            />
            {v.node.label}
          </li>
        )
      })}
    </ul>
  )
}
