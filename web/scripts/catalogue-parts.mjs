// How message catalogues split into parts (src/i18n/catalogue-parts.json), shared by the Vite plugin that builds each
// part and the size check. A message is in the part one of whose prefixes is the first segment of its id, else core.
import { readFileSync } from 'node:fs'

const definition = JSON.parse(readFileSync(new URL('../src/i18n/catalogue-parts.json', import.meta.url), 'utf8'))

/** The parts besides core, by name. */
export const parts = Object.fromEntries(Object.entries(definition).filter(([name]) => !name.startsWith('$')))

/** The part a message belongs to. */
export function partOf(id) {
  const prefix = id.split('.')[0]
  return Object.entries(parts).find(([, part]) => part.prefixes.includes(prefix))?.[0] ?? 'core'
}

/** The messages of [messages] that are in [part]. */
export function pick(messages, part) {
  return Object.fromEntries(Object.entries(messages).filter(([id]) => partOf(id) === part))
}
