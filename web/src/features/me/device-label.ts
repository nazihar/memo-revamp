/** "Firefox on Windows" from a user agent; good enough to recognise one's own devices, never used for security. */
export function deviceLabel(userAgent: string | undefined | null): { browser?: string; os?: string } {
  if (!userAgent) return {}
  const browser = [
    ['Edge', /Edg\//],
    ['Firefox', /Firefox\//],
    ['Chrome', /Chrome\//],
    ['Safari', /Safari\//],
  ] as const
  const os = [
    ['iOS', /iPhone|iPad/],
    ['Android', /Android/],
    ['Windows', /Windows/],
    ['macOS', /Mac OS X|Macintosh/],
    ['Linux', /Linux/],
  ] as const
  const found = (list: readonly (readonly [string, RegExp])[]) => list.find(([, re]) => re.test(userAgent))?.[0]
  const result: { browser?: string; os?: string } = {}
  const b = found(browser)
  const o = found(os)
  if (b) result.browser = b
  if (o) result.os = o
  return result
}
