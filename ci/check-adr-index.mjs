#!/usr/bin/env node
// ADR hygiene (ADR-0001): every docs/adr/NNNN-*.md is listed in docs/adr/README.md, numbers are unique and
// contiguous, every ADR has front matter with a status, and the index lists nothing that does not exist.
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'docs', 'adr')
const files = readdirSync(dir).filter((f) => /^\d{4}-.+\.md$/.test(f)).sort()
const index = readFileSync(join(dir, 'README.md'), 'utf8')
const problems = []

files.forEach((file, i) => {
  const number = Number(file.slice(0, 4))
  if (number !== i + 1) problems.push(`${file}: expected number ${String(i + 1).padStart(4, '0')} (no gaps or duplicates)`)
  if (!index.includes(`(${file})`)) problems.push(`${file}: missing from docs/adr/README.md`)
  const text = readFileSync(join(dir, file), 'utf8')
  if (!/^---\n(?:[\s\S]*?\n)?status:\s*\S+[\s\S]*?\n---/.test(text.replace(/\r\n/g, '\n'))) {
    problems.push(`${file}: missing MADR front matter with a status`)
  }
})
for (const [, linked] of index.matchAll(/\]\(([^)]+\.md)\)/g)) {
  if (linked !== 'template.md' && !files.includes(linked)) problems.push(`README.md links to missing ${linked}`)
}

if (problems.length > 0) {
  console.error(`ADR index check failed:\n  ${problems.join('\n  ')}`)
  process.exit(1)
}
console.log(`ADR index OK (${files.length} ADRs)`)
