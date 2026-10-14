// Renders web/security-headers.json (the single source of the SPA's security headers) as nginx add_header lines.
//   node render-headers.mjs security-headers.json > security-headers.conf
import { readFileSync } from 'node:fs'

const headers = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const lines = Object.entries(headers)
  .filter(([name]) => !name.startsWith('$'))
  .map(([name, value]) => {
    if (!/^[A-Za-z0-9-]+$/.test(name) || /["\n\r]/.test(value)) throw new Error(`unsafe header: ${name}`)
    return `add_header ${name} "${value}" always;`
  })
process.stdout.write(`# Generated from web/security-headers.json — do not edit.\n${lines.join('\n')}\n`)
