/** A determinate progress bar (design E06 §16): [label] names it; the value is a percentage. */
export function ProgressBar({ label, percent }: { label: string; percent: number }) {
  const value = Math.max(0, Math.min(100, Math.round(percent)))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value}
      className="h-3 w-full overflow-hidden rounded-full bg-muted"
    >
      <div className="h-full bg-primary" style={{ inlineSize: `${String(value)}%` }} />
    </div>
  )
}
