/** `yyyy-MM-dd`: forma civil da data, que é o que viaja na URL e o que o usuário escolhe. */
const CIVIL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Converte a data escolhida no calendário para `yyyy-MM-dd` **no fuso do usuário**.
 * `toISOString()` não serve aqui: às 21h de Brasília ele já devolveria o dia seguinte.
 */
export function toCivilDate(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * `Date` local à meia-noite, para alimentar o `mat-datepicker`. Devolve `null` para
 * qualquer coisa que não seja um dia que exista: `2026-02-31` casa com a expressão mas
 * o `Date` o normalizaria para março, e a ida e volta é o que denuncia isso.
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

/** Devolve a data civil válida ou `null`; usado para filtrar o que chega pela URL. */
export function parseCivilDate(raw: string | null | undefined): string | null {
  const date = toLocalDate(raw);
  return date ? toCivilDate(date) : null;
}

/**
 * Início do dia civil, como instante ISO — o formato que `AuditController` aceita
 * (`@DateTimeFormat(ISO.DATE_TIME)` sobre um `Instant`).
 */
export function startOfDayInstant(civilDate: string): string | null {
  const date = toLocalDate(civilDate);
  return date ? date.toISOString() : null;
}

/**
 * Fim do dia civil, como instante ISO. `AuditSpecifications` usa `lessThanOrEqualTo`
 * em `createdAt`, então mandar a meia-noite do próprio dia devolveria uma página vazia
 * para o último dia escolhido: quem filtra "até 17/09/2026" espera ver o dia 17 inteiro.
 */
export function endOfDayInstant(civilDate: string): string | null {
  const date = toLocalDate(civilDate);
  if (!date) {
    return null;
  }
  date.setHours(23, 59, 59, 999);
  return date.toISOString();
}
