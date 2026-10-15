import { defineConfig } from '@hey-api/openapi-ts'

// Generated client for our API (AGENTS.md §2). Input is the committed contract, never a running server.
// @hey-api/openapi-ts is pre-1.0: the version is pinned exactly in package.json (ADR-0002).
export default defineConfig({
  input: '../contracts/openapi.json',
  output: { path: 'src/api/generated' },
  plugins: ['@hey-api/client-fetch', '@hey-api/typescript', '@hey-api/sdk', '@tanstack/react-query'],
})
