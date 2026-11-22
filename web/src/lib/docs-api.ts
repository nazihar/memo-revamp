/** The editor's global, defined by the script the document server serves (ADR-0037). */
export interface DocsApi {
  DocEditor: new (element: string, config: object) => { destroyEditor: () => void }
}
declare global {
  interface Window {
    DocsAPI?: DocsApi
  }
}

/** Loads the document server's editor script once (the memo's document, the minutes' draft). */
export function loadEditorScript(src: string): Promise<void> {
  const existing = document.querySelector<HTMLScriptElement>(`script[data-editor="${src}"]`)
  if (existing && window.DocsAPI) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = src
    script.dataset.editor = src
    script.onload = () => {
      resolve()
    }
    script.onerror = () => {
      reject(new Error('editor script'))
    }
    document.head.append(script)
  })
}

/**
 * Runs [create] without inline event-handler attributes: the editor script sets an empty `onmousewheel` on its frame (a
 * Safari workaround) that our CSP refuses anyway. Skipping it keeps the CSP free of `unsafe-hashes` and the console clean.
 */
export function withoutInlineHandlers<T>(create: () => T): T {
  // eslint-disable-next-line @typescript-eslint/unbound-method -- restored below and only ever called with an element
  const setAttribute = Element.prototype.setAttribute
  Element.prototype.setAttribute = function (this: Element, name: string, value: string) {
    if (!/^on/i.test(name)) setAttribute.call(this, name, value)
  }
  try {
    return create()
  } finally {
    Element.prototype.setAttribute = setAttribute
  }
}
