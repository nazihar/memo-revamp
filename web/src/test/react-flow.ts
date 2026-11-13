/**
 * What React Flow needs from the browser that jsdom lacks (its testing guide): a ResizeObserver, DOMMatrixReadOnly
 * for the viewport transform, and element sizes so nodes count as measured. Sizes are fixed; tests assert content and
 * roles, never positions.
 */
export function installReactFlowStubs() {
  class ResizeObserverStub {
    constructor(private readonly callback: ResizeObserverCallback) {}
    observe(target: Element) {
      this.callback([{ target } as ResizeObserverEntry], this)
    }
    unobserve() {
      // nothing to stop
    }
    disconnect() {
      // nothing to stop
    }
  }

  class DOMMatrixReadOnlyStub {
    m22: number
    constructor(transform?: string) {
      const scale = /scale\(([\d.]+)\)/.exec(transform ?? '')?.[1]
      this.m22 = scale ? Number(scale) : 1
    }
  }

  globalThis.ResizeObserver = ResizeObserverStub
  globalThis.DOMMatrixReadOnly = DOMMatrixReadOnlyStub as unknown as typeof DOMMatrixReadOnly
  Object.defineProperties(HTMLElement.prototype, {
    offsetHeight: { configurable: true, get: () => 80 },
    offsetWidth: { configurable: true, get: () => 260 },
  })
  ;(SVGElement.prototype as unknown as { getBBox: () => DOMRect }).getBBox = () =>
    ({ x: 0, y: 0, width: 0, height: 0 }) as DOMRect
}
