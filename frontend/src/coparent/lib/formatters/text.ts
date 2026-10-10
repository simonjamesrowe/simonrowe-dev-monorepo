/**
 * "1 conversation", "2 conversations". The plural defaults to the singular plus "s"; pass it for
 * a word that does not follow that ("1 child", "2 children").
 */
export function pluralise(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
