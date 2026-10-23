/** Show local browser time while leaving absent provider facts explicit. */
export function formatTime(value: string | null | undefined): string {
  return value
    ? new Date(value).toLocaleString(undefined, {
        dateStyle: 'medium',
        timeStyle: 'medium',
      })
    : 'Not observed'
}
