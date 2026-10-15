#!/usr/bin/env node
// CycloneDX 1.6 SBOM for the web app's *runtime* (production) dependencies, built from pnpm's own resolution
// (`pnpm licenses list --prod --json`). ADR-0013. Output: build/sbom/web.cdx.json
//
// Why not cyclonedx-npm: it supports npm only. Why not Trivy fs: slow over node_modules on bind mounts; Trivy still
// scans the resulting SBOM for vulnerabilities (`trivy sbom`).
import { execFileSync, execSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const out = join(root, 'build', 'sbom', 'web.cdx.json')
const isWindows = process.platform === 'win32'

const command = 'pnpm --filter web licenses list --prod --json'
const raw = isWindows
  ? execSync(command, { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  : execFileSync('pnpm', command.split(' ').slice(1), { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
const byLicense = JSON.parse(raw)
const webPkg = JSON.parse(readFileSync(join(root, 'web', 'package.json'), 'utf8'))

const purlOf = (name, version) =>
  name.startsWith('@')
    ? `pkg:npm/${encodeURIComponent(name.split('/')[0])}/${name.split('/')[1]}@${version}`
    : `pkg:npm/${name}@${version}`

const licenseEntry = (license) =>
  /^[A-Za-z0-9.+-]+$/.test(license) ? { license: { id: license } } : { expression: license }

const components = []
for (const [license, packages] of Object.entries(byLicense)) {
  for (const pkg of packages) {
    for (const version of pkg.versions) {
      components.push({
        type: 'library',
        'bom-ref': purlOf(pkg.name, version),
        name: pkg.name,
        version,
        purl: purlOf(pkg.name, version),
        licenses: [licenseEntry(license)],
      })
    }
  }
}
components.sort((a, b) => a['bom-ref'].localeCompare(b['bom-ref']))

const bom = {
  bomFormat: 'CycloneDX',
  specVersion: '1.6',
  serialNumber: `urn:uuid:${randomUUID()}`,
  version: 1,
  metadata: {
    timestamp: new Date().toISOString(),
    tools: { components: [{ type: 'application', name: 'memo ci/web-sbom.mjs' }] },
    component: { type: 'application', name: 'memo-web', version: webPkg.version ?? '0.0.0' },
  },
  components,
}

mkdirSync(dirname(out), { recursive: true })
writeFileSync(out, JSON.stringify(bom, null, 2) + '\n')
console.log(`web SBOM: ${components.length} runtime components → ${out}`)
