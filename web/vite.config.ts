import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import { tanstackRouter } from '@tanstack/router-plugin/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

/** Security headers shared with the production static server (E03 design §4). */
const securityHeaders = Object.fromEntries(
  Object.entries(
    JSON.parse(readFileSync(new URL('./security-headers.json', import.meta.url), 'utf8')) as Record<string, string>,
  ).filter(([name]) => !name.startsWith('$')),
)

// The SPA talks to the backend on the same origin (/api); in development and preview Vite proxies to the backend.
const apiProxy = { '/api': { target: process.env.MEMO_API_ORIGIN ?? 'http://localhost:8080', changeOrigin: false } }

export default defineConfig({
  plugins: [tanstackRouter({ target: 'react', autoCodeSplitting: true }), react(), tailwindcss()],
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
    allowedHosts: ['localhost', 'host.docker.internal'],
    strictPort: true,
    proxy: apiProxy,
    headers: securityHeaders,
  },
  build: {
    sourcemap: true,
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
