/** The signed-in person's initial in a circle: the account menu's trigger. */
export function AccountInitial({ name }: { name: string }) {
  return (
    <span
      aria-hidden="true"
      className="flex size-8 items-center justify-center rounded-full bg-primary text-sm font-semibold text-primary-foreground"
    >
      {name.slice(0, 1)}
    </span>
  )
}
