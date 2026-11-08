// Per-locale message catalogues stay small enough to load per locale (E07 research R22). Past the limit, split them per
// route instead of raising it.
import { readFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'

const LIMIT_BYTES = 15 * 1024
const files = ['src/i18n/messages/en.json', 'src/i18n/messages/ar.json']
let failed = false
for (const file of files) {
  const size = gzipSync(readFileSync(file)).length
  const line = `${file}: ${(size / 1024).toFixed(1)} KiB gzip (limit ${LIMIT_BYTES / 1024} KiB)`
  if (size > LIMIT_BYTES) {
    console.error(`${line}: too large; load catalogues per route (research E07 R22)`)
    failed = true
  } else {
    console.log(line)
  }
}
process.exit(failed ? 1 : 0)
