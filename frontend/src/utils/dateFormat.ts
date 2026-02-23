const formatter = new Intl.DateTimeFormat('en-GB', {
  year: 'numeric',
  month: 'long',
  day: 'numeric',
})

export function formatDate(isoDateString: string): string {
  return formatter.format(new Date(isoDateString))
}
