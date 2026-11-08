/** WCAG 2.2 contrast of two `#rrggbb` colours (research E07 R21); the server applies the same rule. */
export function isColour(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value)
}

export function luminance(colour: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(colour.slice(i, i + 2), 16) / 255)
  const [r = 0, g = 0, b = 0] = channels.map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

export function contrast(a: string, b: string): number {
  const [lighter, darker] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (lighter + 0.05) / (darker + 0.05)
}

/** What each theme's colour is checked against: button text (4.5:1) and the page background (3:1). */
export const BRAND_CHECKS = {
  light: [
    { against: '#ffffff', minimum: 4.5 },
    { against: '#f6f7f9', minimum: 3 },
  ],
  dark: [
    { against: '#0d1726', minimum: 4.5 },
    { against: '#10151d', minimum: 3 },
  ],
} as const
