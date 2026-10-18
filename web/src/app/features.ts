/**
 * Build-time feature switches. The patterns gallery (E01 design §5) exists in development and in the `e2e` build mode
 * only; production builds leave it out.
 */
export const patternsEnabled: boolean = import.meta.env.DEV || import.meta.env.MODE === 'e2e'
