import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import { tanstackRouter } from '@tanstack/router-plugin/vite'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import { pick } from './scripts/catalogue-parts.mjs'

/** Security headers shared with the production static server (E03 design §4). */
const securityHeaders = Object.fromEntries(
  Object.entries(
    JSON.parse(readFileSync(new URL('./security-headers.json', import.meta.url), 'utf8')) as Record<string, string>,
  ).filter(([name]) => !name.startsWith('$')),
)

/**
 * Headers on the editor's own responses, as the web container's nginx adds them (`location /office/`): the SPA requires
 * COEP, so the framed editor must answer with it, and only our pages may frame it.
 */
const OFFICE_HEADERS: Record<string, string> = {
  'Cross-Origin-Embedder-Policy': 'require-corp',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'X-Content-Type-Options': 'nosniff',
  'Content-Security-Policy': "frame-ancestors 'self'",
}

// The SPA talks to the backend on the same origin (/api); in development and preview Vite proxies to the backend.
// The document editor (ADR-0037) is under /office/ as in production: the full local stack publishes ONLYOFFICE through
// its gateway on 8081. X-Forwarded-Host carries the sub-path, which is how ONLYOFFICE learns its public base URL.
const apiProxy = {
  '/api': { target: process.env.MEMO_API_ORIGIN ?? 'http://localhost:8080', changeOrigin: false },
  '/office': {
    target: process.env.MEMO_OFFICE_ORIGIN ?? 'http://localhost:8081',
    changeOrigin: false,
    ws: true,
    rewrite: (path: string) => path.replace(/^\/office/, ''),
    headers: { 'X-Forwarded-Proto': 'http' },
    configure: (proxy: {
      on: ((
        event: 'proxyReq' | 'proxyReqWs',
        handler: (req: { setHeader: (n: string, v: string) => void; getHeader: (n: string) => unknown }) => void,
      ) => void) &
        ((event: 'proxyRes', handler: (res: { headers: Record<string, unknown> }) => void) => void)
    }) => {
      // Also on the WebSocket: ONLYOFFICE builds the links to the converted document from that connection's headers.
      for (const event of ['proxyReq', 'proxyReqWs'] as const) {
        proxy.on(event, (req) => {
          req.setHeader('X-Forwarded-Host', `${String(req.getHeader('host'))}/office`)
        })
      }
      proxy.on('proxyRes', (res) => {
        for (const [name, value] of Object.entries(OFFICE_HEADERS)) res.headers[name.toLowerCase()] = value
      })
    },
  },
}

/**
 * Document-server APIs no browser needs (ADR-0047): conversion, commands, DocBuilder, the AI proxy, info and the example
 * and welcome pages. The web container's nginx refuses the same paths; this mirrors it for local runs and the E2E stack.
 */
const OFFICE_DENIED =
  /^\/office\/(converter|convertservice\.ashx|docbuilder|coauthoring\/commandservice\.ashx|command|info|example|welcome|ai-proxy)(\/|\?|$)/i

function officeDeny(): Plugin {
  const deny = (req: { url?: string }, res: { statusCode: number; end: () => void }, next: () => void) => {
    if (OFFICE_DENIED.test(req.url ?? '')) {
      res.statusCode = 403
      res.end()
    } else {
      next()
    }
  }
  return {
    name: 'memo-office-deny',
    configureServer(server) {
      server.middlewares.use(deny)
    },
    configurePreviewServer(server) {
      server.middlewares.use(deny)
    },
  }
}

/**
 * `messages/<locale>.json?part=<part>` is that part of the catalogue (src/i18n/catalogue-parts.json), so each part is its
 * own chunk. It answers with a JavaScript module (moduleType js), so the JSON handling does not parse it again.
 */
function catalogueParts(): Plugin {
  return {
    name: 'memo-catalogue-parts',
    enforce: 'pre',
    load(id) {
      const [file, query] = id.split('?')
      const part = new URLSearchParams(query).get('part')
      if (!file || !part || !/[\\/]i18n[\\/]messages[\\/][^\\/]+\.json$/.test(file)) return null
      const messages = pick(JSON.parse(readFileSync(file, 'utf8')) as Record<string, unknown>, part)
      return { code: `export default ${JSON.stringify(messages)}`, moduleType: 'js' }
    },
  }
}

export default defineConfig({
  plugins: [
    officeDeny(),
    catalogueParts(),
    tanstackRouter({ target: 'react', autoCodeSplitting: true }),
    react(),
    tailwindcss(),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: apiProxy,
  },
  preview: {
    port: 4173,
    // host.docker.internal: the Linux Playwright container used for visual baselines (e2e/update-visual-baselines.sh).
    // .localhost: tenant hosts and the operator console (admin.localhost) in the E07 journeys.
    allowedHosts: ['localhost', '.localhost', 'host.docker.internal'],
    strictPort: true,
    proxy: apiProxy,
    headers: securityHeaders,
  },
  build: {
    // Maps for local debugging only: bundles do not reference them, and images leave them out (.dockerignore).
    sourcemap: 'hidden',
    // Fonts are never inlined as data: URIs: the CSP allows fonts from the app origin only (font-src 'self').
    assetsInlineLimit: (file) => (/\.(woff2?|ttf|otf)$/.test(file) ? false : undefined),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    css: false,
  },
})
