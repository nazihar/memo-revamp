import { Checkbox } from '@/components/ui/form-controls'

/** A checkbox with its label and hint beside it, the whole line a 44 px target. */
export function CheckField({
  label,
  hint,
  checked,
  disabled,
  onChange,
}: {
  label: string
  hint?: string
  checked: boolean
  disabled?: boolean
  onChange: (checked: boolean) => void
}) {
  return (
    <label className="flex min-h-11 items-start gap-3 text-sm">
      <Checkbox
        className="mt-1"
        checked={checked}
        disabled={disabled}
        onChange={(e) => {
          onChange(e.target.checked)
        }}
      />
      <span className="flex flex-col gap-0.5">
        <span className="font-medium">{label}</span>
        {hint ? <span className="text-muted-foreground">{hint}</span> : null}
      </span>
    </label>
  )
}
