// Message catalogues load per locale and per part (E07 research R22, E03 T10): each part of each locale stays small
// enough to load with the route that needs it. Past the limit, split a part further (src/i18n/catalogue-parts.json)
// instead of raising it.
import { readFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { parts, pick } from './catalogue-parts.mjs'

const LIMIT_BYTES = 15 * 1024

let failed = false
for (const file of ['src/i18n/messages/en.json', 'src/i18n/messages/ar.json']) {
  const messages = JSON.parse(readFileSync(file, 'utf8'))
  for (const part of ['core', ...Object.keys(parts)]) {
    const size = gzipSync(JSON.stringify(pick(messages, part))).length
    const line = `${file} [${part}]: ${(size / 1024).toFixed(1)} KiB gzip (limit ${LIMIT_BYTES / 1024} KiB)`
    if (size > LIMIT_BYTES) {
      console.error(`${line}: too large; split it further in src/i18n/catalogue-parts.json (research E07 R22)`)
      failed = true
    } else {
      console.log(line)
    }
  }
}
process.exit(failed ? 1 : 0)
