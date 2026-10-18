/** Tenant identity shown in the frame. Product defaults until E03 resolves the tenant and E07 adds branding. */
export interface TenantBranding {
  name: string | null
  logoUrl: string | null
}

export function useTenantBranding(): TenantBranding {
  return { name: null, logoUrl: null }
}
