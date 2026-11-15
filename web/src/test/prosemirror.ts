/**
 * Layout calls ProseMirror (the note editor, ADR-0049) makes that jsdom does not implement. Empty rectangles are
 * enough for typing and commands in tests; real layout is covered by the browser end-to-end tests.
 */
function emptyRects(): DOMRectList {
  return Object.assign([], { item: () => null })
}

function emptyRect(): DOMRect {
  return { x: 0, y: 0, top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0, toJSON: () => ({}) }
}

/** Adds [name] to [target] only where jsdom lacks it. */
function provide(target: object, name: string, value: unknown) {
  const slots = target as Record<string, unknown>
  if (typeof slots[name] !== 'function') slots[name] = value
}

provide(Range.prototype, 'getClientRects', emptyRects)
provide(Range.prototype, 'getBoundingClientRect', emptyRect)
provide(Element.prototype, 'getClientRects', emptyRects)
provide(document, 'elementFromPoint', () => null)
