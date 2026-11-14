import { compareTexts } from './version-diff'

/** Compares two texts off the main thread (design E02 §3): `{older, newer}` in, the rows out. */
self.onmessage = (event: MessageEvent<{ older: string; newer: string }>) => {
  self.postMessage(compareTexts(event.data.older, event.data.newer))
}
