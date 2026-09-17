/** `yyyy-MM-dd`: civil date form, which is what travels in the URL and what the user picks. */
const CIVIL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Converts the date picked in the calendar to `yyyy-MM-dd` **in the user's time zone**.
 * `toISOString()` is no good here: at 21h in Brasília it would already return the next day.
 */
export function toCivilDate(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Local `Date` at midnight, to feed the `mat-datepicker`. Returns `null` for anything
 * that is not a day that exists: `2026-02-31` matches the expression but `Date` would
 * normalize it to March, and the round trip is what gives that away.
 */
export function toLocalDate(civilDate: string | null | undefined): Date | null {
  const match = CIVIL_DATE.exec((civilDate ?? '').trim());
  if (!match) {
    return null;
  }
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(year, month - 1, day, 0, 0, 0, 0);
  if (Number.isNaN(date.getTime()) || toCivilDate(date) !== match[0]) {
    return null;
  }
  return date;
}

/** Returns the valid civil date or `null`; used to filter what arrives through the URL. */
export function parseCivilDate(raw: string | null | undefined): string | null {
  const date = toLocalDate(raw);
  return date ? toCivilDate(date) : null;
}

/**
 * Start of the civil day, as an ISO instant — the format `AuditController` accepts
 * (`@DateTimeFormat(ISO.DATE_TIME)` over an `Instant`).
 */
export function startOfDayInstant(civilDate: string): string | null {
  const date = toLocalDate(civilDate);
  return date ? date.toISOString() : null;
}

/**
 * End of the civil day, as an ISO instant. `AuditSpecifications` uses `lessThanOrEqualTo`
 * on `createdAt`, so sending midnight of the day itself would return an empty page for
 * the last chosen day: whoever filters "até 17/09/2026" expects to see the whole 17th.
 */
export function endOfDayInstant(civilDate: string): string | null {
  const date = toLocalDate(civilDate);
  if (!date) {
    return null;
  }
  date.setHours(23, 59, 59, 999);
  return date.toISOString();
}
