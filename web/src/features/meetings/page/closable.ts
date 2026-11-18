import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * A dialog that closes itself (its own button, Escape or the close button), so focus returns to what opened it, then
 * tells [onClose]. `onOpenChange` keeps one identity: the dialog listens for `close` with it, and a new function on the
 * render that closes it would miss the event.
 */
export function useClosable(onClose: () => void): {
  open: boolean
  close: () => void
  onOpenChange: (open: boolean) => void
} {
  const [open, setOpen] = useState(true)
  const latest = useRef(onClose)
  useEffect(() => {
    latest.current = onClose
  })
  const onOpenChange = useCallback((next: boolean) => {
    if (!next) latest.current()
  }, [])
  const close = useCallback(() => {
    setOpen(false)
  }, [])
  return { open, close, onOpenChange }
}
