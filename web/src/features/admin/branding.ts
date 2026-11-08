/** The logo's address; the version changes with the logo, so browsers fetch a new one at once. */
export function logoUrl(version: string): string {
  return `/api/v1/branding/logo?v=${encodeURIComponent(version)}`
}
