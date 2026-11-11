import { FlaskConical } from 'lucide-react'

export function DemoModeBanner() {
  return (
    <div
      role="status"
      className="border-b border-amber-400/30 bg-amber-400/10 px-4 py-2.5 text-center md:px-8"
    >
      <p className="inline-flex items-center justify-center gap-2 text-sm font-medium text-amber-100">
        <FlaskConical className="size-4 shrink-0 text-amber-300" aria-hidden />
        <span>
          <span className="font-semibold text-amber-200">Demo Mode — Sample Data</span>
          <span className="text-amber-100/80">
            {' '}
            · Interactive portfolio preview. Values are not live monitoring.
          </span>
        </span>
      </p>
    </div>
  )
}
