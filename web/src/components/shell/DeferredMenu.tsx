import {
  cloneElement,
  Suspense,
  useState,
  type ComponentType,
  type KeyboardEvent,
  type LazyExoticComponent,
  type ReactElement,
} from 'react'

/** What a deferred menu's full component takes: open at once when the person asked for it before it loaded. */
export interface DeferredMenuProps {
  defaultOpen?: boolean
}

interface TriggerProps {
  'aria-haspopup'?: 'menu'
  'aria-expanded'?: boolean
  onClick?: () => void
  onKeyDown?: (event: KeyboardEvent) => void
  onPointerEnter?: () => void
  onFocus?: () => void
}

/**
 * A top-bar menu (theme, language, account) whose code loads when it is first wanted: Radix's menu, its positioning and
 * the items are not in the first page's script (NFR P5). Until then [children], the same button the full menu shows as
 * its trigger, stands in. Pointing at it or focusing it starts the load; pressing it (click, Enter, Space) or ArrowDown
 * swaps in [menu], open. The full menu then works as before, and focus returns to its trigger when it closes.
 */
export function DeferredMenu({
  menu: Menu,
  preload,
  children,
}: {
  menu: LazyExoticComponent<ComponentType<DeferredMenuProps>>
  preload: () => Promise<unknown>
  children: ReactElement<TriggerProps>
}) {
  const [open, setOpen] = useState(false)
  const warm = () => {
    void preload()
  }
  if (!open) {
    return cloneElement(children, {
      'aria-haspopup': 'menu',
      'aria-expanded': false,
      onClick: () => {
        setOpen(true)
      },
      onKeyDown: (event: KeyboardEvent) => {
        if (event.key === 'ArrowDown') {
          event.preventDefault()
          setOpen(true)
        }
      },
      onPointerEnter: warm,
      onFocus: warm,
    })
  }
  return (
    <Suspense fallback={children}>
      <Menu defaultOpen />
    </Suspense>
  )
}
