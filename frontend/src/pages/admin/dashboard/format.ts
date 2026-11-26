/** A LocalDate (`2026-11-26`) as `26 Nov`, read as a calendar date with no time zone shift. */
export function shortDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  })
}

/** A LocalDate as `Thu 26 Nov`. */
export function weekdayDate(isoDate: string): string {
  const [year, month, day] = isoDate.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, day)).toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  })
}

/** An instant as `18 Nov, 01:29`, in the browser's zone. */
export function dateTime(instant: string): string {
  return new Date(instant).toLocaleString('en-GB', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * An uptime percentage. Two decimals, and never rounded up to 100: 99.996% is not a perfect
 * record, so it reads 99.99%.
 */
export function percent(value: number | null): string {
  if (value === null) return '—'
  if (value >= 100) return '100%'
  return `${(Math.floor(value * 100) / 100).toFixed(2)}%`
}

/** A span of minutes as `45m`, `3h 20m` or `2d 4h`. */
export function minutes(total: number): string {
  if (total < 60) return `${total}m`
  const hours = Math.floor(total / 60)
  if (hours < 24) {
    const rest = total % 60
    return rest ? `${hours}h ${rest}m` : `${hours}h`
  }
  const days = Math.floor(hours / 24)
  const restHours = hours % 24
  return restHours ? `${days}d ${restHours}h` : `${days}d`
}

/** How long between two instants, in minutes. */
export function minutesBetween(from: string, to: string): number {
  return Math.max(0, Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60000))
}

/** The smallest of 10, 20, 50, 100, 200... at or above the value, so ticks stay round. */
export function niceCeiling(value: number): number {
  let step = 10
  while (step < value) {
    if (step * 2 >= value) return step * 2
    if (step * 5 >= value) return step * 5
    step *= 10
  }
  return step
}
