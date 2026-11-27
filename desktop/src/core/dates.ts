// Fill & Sign › Date: today's date as people write it where they are (the system's locale, medium style), such as
// "Nov 27, 2026" in the United States or "27 Nov 2026" in the United Kingdom.

export function todayText(date = new Date(), locale: string | undefined = globalThis.navigator?.language): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}
