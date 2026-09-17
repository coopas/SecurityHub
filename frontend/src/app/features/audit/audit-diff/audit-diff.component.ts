import { Component, Input, OnChanges } from '@angular/core';

import { AUDIT_ACTION_LABELS, AuditLog } from '../models/audit.model';
import { AuditDiff, AuditFieldRow, buildAuditDiff } from '../utils/audit-diff.util';

/** Text displayed in place of a missing value. */
export const EMPTY_VALUE = '—';

/**
 * "Before and after" comparison of a row of the trail.
 *
 * The utility's discriminated union is flattened into plain fields because the template
 * does not narrow types inside an `*ngIf`; the `ngSwitch` over `kind` does the rest.
 *
 * Nothing here uses `innerHTML`: every value is interpolated, because the compared fields
 * come from text typed by users (vulnerability title, project name) and this is the
 * screen where a stored XSS would have the administrator as its target.
 */
@Component({
  selector: 'app-audit-diff',
  templateUrl: './audit-diff.component.html',
  styleUrls: ['../audit.scss'],
})
export class AuditDiffComponent implements OnChanges {
  @Input({ required: true }) log!: AuditLog;

  readonly emptyValue = EMPTY_VALUE;

  kind: AuditDiff['kind'] = 'empty';
  /** Fields of a CREATE or of a DELETE, which have a single side. */
  fields: AuditFieldRow[] = [];
  changed: AuditFieldRow[] = [];
  unchanged: AuditFieldRow[] = [];
  rawOldText: string | null = null;
  rawNewText: string | null = null;
  showUnchanged = false;

  ngOnChanges(): void {
    this.apply(buildAuditDiff(this.log?.oldValue, this.log?.newValue));
  }

  /**
   * An action with no fields on either side is the correct record of LOGIN, LOGIN_FAILED
   * or REGISTER: who, when and from where is already the complete information. Saying
   * that in text keeps the operator from reading an empty panel as a failure of the
   * screen.
   */
  get emptyExplanation(): string {
    const action = AUDIT_ACTION_LABELS[this.log.action] ?? this.log.action;
    return `${action} não altera campos: o registro guarda o autor, o horário e a origem da ação.`;
  }

  get unchangedToggleLabel(): string {
    const noun = this.unchanged.length === 1 ? 'campo inalterado' : 'campos inalterados';
    const verb = this.showUnchanged ? 'Ocultar' : 'Mostrar';
    return `${verb} ${this.unchanged.length} ${noun}`;
  }

  toggleUnchanged(): void {
    this.showUnchanged = !this.showUnchanged;
  }

  text(value: string | null): string {
    return value ?? EMPTY_VALUE;
  }

  /** Text summary of the change, for whoever reads the comparison with a screen reader. */
  changeLabel(row: AuditFieldRow): string {
    return `${row.label}: de ${this.text(row.oldText)} para ${this.text(row.newText)}`;
  }

  trackByField(_index: number, row: AuditFieldRow): string {
    return row.field;
  }

  private apply(diff: AuditDiff): void {
    this.kind = diff.kind;
    this.fields = diff.kind === 'created' || diff.kind === 'deleted' ? diff.fields : [];
    this.changed = diff.kind === 'updated' ? diff.changed : [];
    this.unchanged = diff.kind === 'updated' ? diff.unchanged : [];
    this.rawOldText = diff.kind === 'raw' ? diff.oldText : null;
    this.rawNewText = diff.kind === 'raw' ? diff.newText : null;
    this.showUnchanged = false;
  }
}
