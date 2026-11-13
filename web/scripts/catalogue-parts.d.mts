export declare const parts: Record<string, { routes: string[]; prefixes: string[] }>
export declare function partOf(id: string): string
export declare function pick<T>(messages: Record<string, T>, part: string): Record<string, T>
