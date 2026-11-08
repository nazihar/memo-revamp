import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { getBrandingOptions } from '@/api/generated/@tanstack/react-query.gen'

/**
 * Applies the organisation's colours (design E07 §6) as CSS custom properties through the CSSOM, which the strict CSP
 * allows (no inline style attribute or element). Each theme picks its own colour in tokens.css; none set, the defaults.
 */
export function BrandingApplier() {
  const branding = useQuery({ ...getBrandingOptions(), staleTime: 5 * 60_000 })
  const light = branding.data?.colourLight
  const dark = branding.data?.colourDark
  useEffect(() => {
    const root = document.documentElement.style
    if (light) root.setProperty('--brand-light', light)
    else root.removeProperty('--brand-light')
    if (dark) root.setProperty('--brand-dark', dark)
    else root.removeProperty('--brand-dark')
  }, [light, dark])
  return null
}
