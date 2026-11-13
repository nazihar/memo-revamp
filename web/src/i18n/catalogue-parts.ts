import definition from './catalogue-parts.json'

/**
 * A slice of a locale's message catalogue that loads with the routes that need it (catalogue-parts.json; the build
 * splits the catalogues with scripts/catalogue-parts.mjs).
 */
export type CataloguePart = 'core' | 'admin' | 'builder' | 'platform' | 'patterns'

const routes = Object.entries(definition)
  .filter(([name]) => !name.startsWith('$'))
  .map(([name, part]) => [name as CataloguePart, (part as { routes: string[] }).routes] as const)

/** Every part a page at [pathname] needs besides core (the workflow builder's pages need admin and builder). */
export function partsForPath(pathname: string): CataloguePart[] {
  return routes
    .filter(([, prefixes]) => prefixes.some((route) => pathname === route || pathname.startsWith(`${route}/`)))
    .map(([name]) => name)
}

/** The first part a page at [pathname] needs besides core, if any. */
export function partForPath(pathname: string): CataloguePart {
  const found = routes.find(([, prefixes]) =>
    prefixes.some((route) => pathname === route || pathname.startsWith(`${route}/`)),
  )
  return found?.[0] ?? 'core'
}
