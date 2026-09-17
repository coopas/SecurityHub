import { Component, Input, OnChanges } from '@angular/core';

import { AUDIT_ACTION_LABELS, AuditLog } from '../models/audit.model';
import { AuditDiff, AuditFieldRow, buildAuditDiff } from '../utils/audit-diff.util';

/** Texto exibido no lugar de um valor ausente. */
export const EMPTY_VALUE = '—';

/**
 * Comparação "antes e depois" de uma linha da trilha.
 *
 * A união discriminada do utilitário é achatada em campos simples porque o template
 * não estreita tipos dentro de um `*ngIf`; o `ngSwitch` sobre `kind` faz o resto.
 *
 * Nada aqui usa `innerHTML`: todo valor é interpolado, porque os campos comparados vêm
 * de texto digitado por usuários (título de vulnerabilidade, nome de projeto) e esta é
 * a tela onde um XSS armazenado teria o administrador como alvo.
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
  /** Campos de um CREATE ou de um DELETE, que têm um lado só. */
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
   * Uma ação sem campos dos dois lados é o registro correto de LOGIN, LOGIN_FAILED ou
   * REGISTER: quem, quando e de onde já é a informação completa. Dizer isso em texto
   * evita que o operador leia um painel vazio como falha da tela.
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

  /** Resumo em texto da mudança, para quem lê a comparação com leitor de tela. */
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
