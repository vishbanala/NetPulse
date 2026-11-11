/** Build-time flag for the public Render portfolio demo. */
export function isDemoMode(): boolean {
  return import.meta.env.VITE_DEMO_MODE === 'true'
}
