/**
 * jsdom lacks HTMLDialogElement.showModal/close. This test-only polyfill mimics what browsers do that our components
 * rely on (ADR-0021): set `open`, move focus to the autofocus element or the first focusable one, close on Escape, fire
 * `close`. Focus restoration is our component's job, so it is not polyfilled; tests exercise it.
 */
const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

export function installDialogPolyfill(): void {
  const proto = HTMLDialogElement.prototype as HTMLDialogElement & { __polyfilled?: boolean }
  if (typeof proto.showModal === 'function' || proto.__polyfilled) return
  proto.__polyfilled = true
  proto.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
    const target = this.querySelector<HTMLElement>('[autofocus]') ?? this.querySelector<HTMLElement>(FOCUSABLE)
    target?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        this.removeEventListener('keydown', onKey)
        this.close()
      }
    }
    this.addEventListener('keydown', onKey)
  }
  proto.show = function show(this: HTMLDialogElement) {
    this.showModal()
  }
  proto.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
  Object.defineProperty(proto, 'open', {
    configurable: true,
    get(this: HTMLDialogElement) {
      return this.hasAttribute('open')
    },
  })
}
