export function LoadingState({ label = 'Loading monitoring data…' }: { label?: string }) {
  return (
    <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4">
      <div
        className="size-10 animate-spin rounded-full border-2 border-line border-t-pulse"
        role="status"
        aria-label={label}
      />
      <p className="font-mono text-sm text-mist">{label}</p>
    </div>
  )
}
