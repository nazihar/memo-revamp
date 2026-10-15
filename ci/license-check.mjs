#!/usr/bin/env node
// Licence gate over CycloneDX SBOMs (AGENTS.md §3.1, ADR-0013).
//
//   node ci/license-check.mjs [--report build/sbom/licenses.md] <sbom.json>...
//   node ci/license-check.mjs --self-test
//
// A component passes when *every* licence it declares is on the allow-list (for "A OR B" expressions, at least one
// alternative must be allowed). Anything else fails unless ci/license-exceptions.json lists the exact component with a
// reason. AGPL/GPL software may only run as a separate, unmodified service and must never appear in our SBOMs.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

export const ALLOWED = new Set([
  // AGENTS.md §3.1
  'Apache-2.0', 'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC', 'EPL-2.0', 'MPL-2.0',
  'LGPL-2.1-only', 'LGPL-2.1-or-later', 'LGPL-3.0-only', 'LGPL-3.0-or-later', // unmodified library use only
  // Permissive equivalents (no copyleft obligations beyond attribution)
  '0BSD', 'MIT-0', 'Unlicense', 'CC0-1.0', 'BlueOak-1.0.0', 'Python-2.0', 'Zlib', 'W3C', 'PostgreSQL', 'CC-BY-4.0',
  'EDL-1.0', 'Bouncy-Castle', 'UPL-1.0',
])

/** Common non-SPDX spellings found in Maven POMs and npm manifests. */
const ALIASES = new Map(
  Object.entries({
    'the apache software license, version 2.0': 'Apache-2.0',
    'the apache license, version 2.0': 'Apache-2.0',
    'apache license, version 2.0': 'Apache-2.0',
    'apache license 2.0': 'Apache-2.0',
    'apache 2.0': 'Apache-2.0',
    'apache-2.0': 'Apache-2.0',
    'the mit license': 'MIT',
    'mit license': 'MIT',
    'bsd license 3': 'BSD-3-Clause',
    'the bsd license': 'BSD-3-Clause',
    'new bsd license': 'BSD-3-Clause',
    'bsd-2-clause': 'BSD-2-Clause',
    'eclipse public license v2.0': 'EPL-2.0',
    'eclipse public license - v 2.0': 'EPL-2.0',
    'eclipse distribution license - v 1.0': 'EDL-1.0',
    'gnu lesser general public license': 'LGPL-2.1-only',
    'lgpl-2.1': 'LGPL-2.1-only',
    'mpl 2.0': 'MPL-2.0',
    'public domain': 'CC0-1.0',
    'postgresql license': 'PostgreSQL',
    'universal permissive license, version 1.0': 'UPL-1.0',
  }),
)

function normalise(name) {
  if (!name) return null
  return ALIASES.get(name.trim().toLowerCase()) ?? name.trim()
}

/** Licence alternatives of one component: an array of "all of these" sets, any one of which must be fully allowed. */
export function licenceOptions(component) {
  const entries = component.licenses ?? []
  if (entries.length === 0) return []
  const options = []
  for (const e of entries) {
    if (e.expression) {
      for (const alt of e.expression.replace(/[()]/g, '').split(/\s+OR\s+/i)) {
        options.push(alt.split(/\s+AND\s+/i).map((s) => normalise(s.replace(/\s+WITH\s+.*/i, ''))))
      }
    } else {
      options.push([normalise(e.license?.id ?? e.license?.name)])
    }
  }
  // Several plain `license` entries on one component mean the component is under all of them (dual-listing in POMs
  // usually means "choose"; we are conservative only when none is allowed).
  return options
}

export function evaluate(components, exceptions = []) {
  const failures = []
  const rows = []
  for (const c of components) {
    const id = `${c.group ? c.group + ':' : ''}${c.name}@${c.version}`
    const options = licenceOptions(c)
    const ok = options.some((all) => all.every((l) => l && ALLOWED.has(l)))
    const exception = exceptions.find((x) => x.component === id || x.component === `${c.group ? c.group + ':' : ''}${c.name}`)
    const label = options.map((o) => o.join(' AND ')).join(' OR ') || 'UNKNOWN'
    let status = ok ? 'allowed' : exception ? `exception: ${exception.reason}` : 'REJECTED'
    if (!ok && exception && !exception.reason?.trim()) status = 'REJECTED (exception without reason)'
    if (status.startsWith('REJECTED')) failures.push(`${id} — ${label}`)
    rows.push({ id, label, status })
  }
  return { failures, rows }
}

/** Our own modules (Gradle group com.memo) are first-party, not third-party licences. */
const FIRST_PARTY_GROUP = 'com.memo'

function loadComponents(file) {
  const bom = JSON.parse(readFileSync(file, 'utf8'))
  return (bom.components ?? []).filter(
    (c) => (c.type === 'library' || c.type === 'framework') && c.group !== FIRST_PARTY_GROUP,
  )
}

function selfTest() {
  const lib = (name, licenses) => ({ type: 'library', name, version: '1.0.0', licenses })
  const cases = [
    [lib('ok-spdx', [{ license: { id: 'Apache-2.0' } }]), true],
    [lib('ok-alias', [{ license: { name: 'The Apache Software License, Version 2.0' } }]), true],
    [lib('ok-or', [{ expression: '(MIT OR GPL-3.0-only)' }]), true],
    [lib('gpl', [{ license: { id: 'GPL-3.0-only' } }]), false],
    [lib('agpl', [{ license: { id: 'AGPL-3.0-only' } }]), false],
    [lib('and-gpl', [{ expression: 'MIT AND GPL-2.0-only' }]), false],
    [lib('unknown', []), false],
    [lib('sspl', [{ license: { name: 'Server Side Public License' } }]), false],
  ]
  let bad = 0
  for (const [component, expectedOk] of cases) {
    const { failures } = evaluate([component])
    if ((failures.length === 0) !== expectedOk) {
      console.error(`self-test FAILED: ${component.name} expected ${expectedOk ? 'allowed' : 'rejected'}`)
      bad++
    }
  }
  const noReason = evaluate([lib('gpl-excepted', [{ license: { id: 'GPL-3.0-only' } }])], [{ component: 'gpl-excepted', reason: ' ' }])
  if (noReason.failures.length !== 1) {
    console.error('self-test FAILED: exception without a reason must not pass')
    bad++
  }
  const withReason = evaluate([lib('gpl-excepted', [{ license: { id: 'GPL-3.0-only' } }])], [{ component: 'gpl-excepted', reason: 'test' }])
  if (withReason.failures.length !== 0) {
    console.error('self-test FAILED: exception with a reason must pass')
    bad++
  }
  console.log(bad === 0 ? 'license-check self-test: OK' : `license-check self-test: ${bad} failure(s)`)
  process.exit(bad === 0 ? 0 : 1)
}

function main(argv) {
  if (argv.includes('--self-test')) return selfTest()
  let report = null
  const files = []
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--report') report = argv[++i]
    else files.push(argv[i])
  }
  if (files.length === 0) {
    console.error('usage: license-check.mjs [--report out.md] <sbom.json>...')
    process.exit(2)
  }
  const exceptions = JSON.parse(readFileSync(join(here, 'license-exceptions.json'), 'utf8')).exceptions
  let allFailures = []
  const sections = []
  for (const file of files) {
    const { failures, rows } = evaluate(loadComponents(file), exceptions)
    allFailures = allFailures.concat(failures)
    sections.push(
      `## ${file.replace(/\\/g, '/')}\n\n${rows.length} components.\n\n| Component | Licence | Status |\n|---|---|---|\n` +
        rows
          .sort((a, b) => a.id.localeCompare(b.id))
          .map((r) => `| ${r.id} | ${r.label} | ${r.status} |`)
          .join('\n'),
    )
  }
  if (report) {
    mkdirSync(dirname(report), { recursive: true })
    writeFileSync(
      report,
      `# Third-party licence report\n\nGenerated by \`ci/license-check.mjs\` from the CycloneDX SBOMs (runtime dependencies only). ` +
        `Allow-list: AGENTS.md §3.1. Separately-run services (ONLYOFFICE, ClamAV, …) are listed in docs/dependencies.md.\n\n` +
        sections.join('\n\n') +
        '\n',
    )
    console.log(`licence report → ${report}`)
  }
  if (allFailures.length > 0) {
    console.error(`Licence check FAILED (${allFailures.length}):\n  ${allFailures.join('\n  ')}`)
    process.exit(1)
  }
  console.log('Licence check passed.')
}

main(process.argv.slice(2))
