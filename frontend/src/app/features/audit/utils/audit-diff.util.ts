import { AuditValues, auditFieldLabel } from '../models/audit.model';

/**
 * One of the sides of the comparison, already normalized.
 *
 * - `absent`: there was no value. It is the legitimate case of LOGIN and REGISTER (both
 *   sides), of the old side of a CREATE and of the new side of a DELETE.
 * - `values`: object of fields, the normal case.
 * - `raw`: something that is not an object of fields came in — typically a truncated JSON
 *   (the backend cuts at 8000 characters) or a malformed one. Displayed as raw text,
 *   never as an empty panel and never as an exception.
 */
export type AuditSide =
  | { kind: 'absent' }
  | { kind: 'values'; values: AuditValues }
  | { kind: 'raw'; text: string };

export interface AuditFieldRow {
  field: string;
  label: string;
  /** `null` means "missing key", which in the trail amounts to a null value. */
  oldText: string | null;
  newText: string | null;
  changed: boolean;
}

export type AuditDiff =
  /** Action with no fields on either side: correct and deliberate, not an error. */
  | { kind: 'empty' }
  | { kind: 'created'; fields: AuditFieldRow[] }
  | { kind: 'deleted'; fields: AuditFieldRow[] }
  | { kind: 'updated'; changed: AuditFieldRow[]; unchanged: AuditFieldRow[] }
  | { kind: 'raw'; oldText: string | null; newText: string | null };

const ABSENT: AuditSide = { kind: 'absent' };

/**
 * Normalizes one side of the comparison without ever throwing.
 *
 * The current contract delivers an already deserialized object (`AuditLogResponse`
 * exposes `Map<String, Object>`), but the database column stores text and the mapper
 * returns `null` when it cannot parse. Accepting `string` here is what guarantees that a
 * truncated row, one in an old format or one coming from another serializer shows up as
 * raw text instead of breaking the whole page.
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
      // Truncated or malformed JSON: the raw text is still audit information.
      return { kind: 'raw', text };
    }
  }

  if (isValues(value)) {
    return Object.keys(value).length === 0 ? ABSENT : { kind: 'values', values: value };
  }

  // Array or scalar: it is not a map of fields, so it becomes text.
  return { kind: 'raw', text: formatValue(value) };
}

/** Builds the comparison shown on screen from the two raw sides of the API. */
export function buildAuditDiff(oldValue: unknown, newValue: unknown): AuditDiff {
  const before = readAuditSide(oldValue);
  const after = readAuditSide(newValue);

  if (before.kind === 'absent' && after.kind === 'absent') {
    return { kind: 'empty' };
  }

  // One unreadable side is enough for the field-by-field comparison to stop being
  // trustworthy: we show both sides as text and let the operator judge.
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
    // Unreachable: the cases above cover all the remaining combinations. It stays here
    // so the compiler closes the union without a cast.
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
    // Compare the displayed text, and not the references: two values the screen draws
    // identically cannot show up marked as changed.
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

/** Old-side keys first, preserving the order in which the backend recorded them. */
function mergeKeys(before: AuditValues, after: AuditValues): string[] {
  const keys = Object.keys(before);
  for (const key of Object.keys(after)) {
    if (!keys.includes(key)) {
      keys.push(key);
    }
  }
  return keys;
}

/** `null` for a missing key or a null value — both mean "no value" in the trail. */
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
 * Text of a value. The result is always interpolated in the template (`{{ }}`), never
 * `innerHTML`: the values come from fields filled in by users — vulnerability title,
 * project name — and this is exactly the screen where a stored XSS would be fired by an
 * administrator.
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
