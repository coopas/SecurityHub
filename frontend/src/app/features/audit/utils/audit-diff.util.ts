import { AuditValues, auditFieldLabel } from '../models/audit.model';

/**
 * Um dos lados da comparação, já normalizado.
 *
 * - `absent`: não houve valor. É o caso legítimo de LOGIN e REGISTER (os dois lados),
 *   do lado antigo de um CREATE e do lado novo de um DELETE.
 * - `values`: objeto de campos, o caso normal.
 * - `raw`: veio algo que não é um objeto de campos — tipicamente um JSON truncado
 *   (o backend corta em 8000 caracteres) ou malformado. Exibido como texto bruto,
 *   nunca como panel vazio e nunca como exceção.
 */
export type AuditSide =
  | { kind: 'absent' }
  | { kind: 'values'; values: AuditValues }
  | { kind: 'raw'; text: string };

export interface AuditFieldRow {
  field: string;
  label: string;
  /** `null` significa "chave ausente", que na trilha equivale a valor nulo. */
  oldText: string | null;
  newText: string | null;
  changed: boolean;
}

export type AuditDiff =
  /** Ação sem campos dos dois lados: correto e deliberado, não um erro. */
  | { kind: 'empty' }
  | { kind: 'created'; fields: AuditFieldRow[] }
  | { kind: 'deleted'; fields: AuditFieldRow[] }
  | { kind: 'updated'; changed: AuditFieldRow[]; unchanged: AuditFieldRow[] }
  | { kind: 'raw'; oldText: string | null; newText: string | null };

const ABSENT: AuditSide = { kind: 'absent' };

/**
 * Normaliza um lado da comparação sem nunca lançar.
 *
 * O contrato atual entrega um objeto já desserializado (`AuditLogResponse` expõe
 * `Map<String, Object>`), mas a coluna do banco guarda texto e o mapper devolve `null`
 * quando não consegue parsear. Aceitar `string` aqui é o que garante que uma linha
 * truncada, de formato antigo ou vinda de outro serializador apareça como texto bruto
 * em vez de quebrar a página inteira.
 */
export function readAuditSide(value: unknown): AuditSide {
  if (value === null || value === undefined) {
    return ABSENT;
  }

  if (typeof value === 'string') {
    const text = value.trim();
    if (!text) {
      return ABSENT;
    }
    try {
      const parsed: unknown = JSON.parse(text);
      return isValues(parsed) ? { kind: 'values', values: parsed } : { kind: 'raw', text };
    } catch {
      // JSON truncado ou malformado: o texto cru ainda é informação de auditoria.
      return { kind: 'raw', text };
    }
  }

  if (isValues(value)) {
    return Object.keys(value).length === 0 ? ABSENT : { kind: 'values', values: value };
  }

  // Array ou escalar: não é um mapa de campos, então vira texto.
  return { kind: 'raw', text: formatValue(value) };
}

/** Monta a comparação exibida na tela a partir dos dois lados brutos da API. */
export function buildAuditDiff(oldValue: unknown, newValue: unknown): AuditDiff {
  const before = readAuditSide(oldValue);
  const after = readAuditSide(newValue);

  if (before.kind === 'absent' && after.kind === 'absent') {
    return { kind: 'empty' };
  }

  // Basta um lado ilegível para que a comparação campo a campo deixe de ser confiável:
  // mostramos os dois lados como texto e deixamos o operador julgar.
  if (before.kind === 'raw' || after.kind === 'raw') {
    return { kind: 'raw', oldText: sideText(before), newText: sideText(after) };
  }

  if (before.kind === 'absent' && after.kind === 'values') {
    return { kind: 'created', fields: singleSideRows(after.values, 'new') };
  }

  if (before.kind === 'values' && after.kind === 'absent') {
    return { kind: 'deleted', fields: singleSideRows(before.values, 'old') };
  }

  if (before.kind !== 'values' || after.kind !== 'values') {
    // Inalcançável: os casos acima cobrem todas as combinações restantes. Fica aqui
    // para que o compilador feche a união sem um cast.
    return { kind: 'empty' };
  }

  const changed: AuditFieldRow[] = [];
  const unchanged: AuditFieldRow[] = [];
  for (const field of mergeKeys(before.values, after.values)) {
    const row = compareField(field, before.values, after.values);
    (row.changed ? changed : unchanged).push(row);
  }
  return { kind: 'updated', changed, unchanged };
}

function compareField(field: string, before: AuditValues, after: AuditValues): AuditFieldRow {
  const oldText = textOf(before, field);
  const newText = textOf(after, field);
  return {
    field,
    label: auditFieldLabel(field),
    oldText,
    newText,
    // Comparar o texto exibido, e não as referências: dois valores que a tela desenha
    // de forma idêntica não podem aparecer marcados como alterados.
    changed: oldText !== newText,
  };
}

function singleSideRows(values: AuditValues, side: 'old' | 'new'): AuditFieldRow[] {
  return Object.keys(values).map((field) => {
    const text = textOf(values, field);
    return {
      field,
      label: auditFieldLabel(field),
      oldText: side === 'old' ? text : null,
      newText: side === 'new' ? text : null,
      changed: true,
    };
  });
}

/** Chaves do lado antigo primeiro, preservando a ordem em que o backend as gravou. */
function mergeKeys(before: AuditValues, after: AuditValues): string[] {
  const keys = Object.keys(before);
  for (const key of Object.keys(after)) {
    if (!keys.includes(key)) {
      keys.push(key);
    }
  }
  return keys;
}

/** `null` para chave ausente ou valor nulo — os dois significam "sem valor" na trilha. */
function textOf(values: AuditValues, field: string): string | null {
  if (!Object.prototype.hasOwnProperty.call(values, field)) {
    return null;
  }
  const value = values[field];
  return value === null || value === undefined ? null : formatValue(value);
}

function sideText(side: AuditSide): string | null {
  if (side.kind === 'absent') {
    return null;
  }
  return side.kind === 'raw' ? side.text : formatValue(side.values);
}

/**
 * Texto de um valor. O resultado é sempre interpolado no template (`{{ }}`), nunca
 * `innerHTML`: os valores vêm de campos preenchidos por usuários — título de
 * vulnerabilidade, nome de projeto — e essa é exatamente a tela onde um XSS
 * armazenado seria disparado por um administrador.
 */
export function formatValue(value: unknown): string {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2) ?? String(value);
  } catch {
    return String(value);
  }
}

function isValues(value: unknown): value is AuditValues {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
