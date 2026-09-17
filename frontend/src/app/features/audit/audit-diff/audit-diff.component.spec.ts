import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { SharedModule } from '../../../shared/shared.module';
import { AuditLog } from '../models/audit.model';
import { makeAuditLog, makeVulnerabilitySnapshot } from '../testing/audit-test-utils';
import { AuditDiffComponent } from './audit-diff.component';

describe('AuditDiffComponent', () => {
  let fixture: ComponentFixture<AuditDiffComponent>;
  let component: AuditDiffComponent;

  /** O input não é ligado por template aqui, então `ngOnChanges` é acionado à mão. */
  const render = (log: AuditLog): void => {
    component.log = log;
    component.ngOnChanges();
    fixture.detectChanges();
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => element().textContent ?? '';
  const find = (testId: string): HTMLElement | null =>
    element().querySelector<HTMLElement>(`[data-testid="${testId}"]`);

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [AuditDiffComponent],
      imports: [SharedModule, NoopAnimationsModule],
    });

    fixture = TestBed.createComponent(AuditDiffComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => TestBed.resetTestingModule());

  it('CREATE: mostra só o lado novo, sem painel de valor anterior', () => {
    render(
      makeAuditLog({
        action: 'CREATE',
        oldValue: undefined,
        newValue: { title: 'SQL Injection no login', severity: 'HIGH' },
      }),
    );

    expect(component.kind).toBe('created');
    expect(find('audit-diff-created')).not.toBeNull();
    expect(find('audit-diff-changed')).toBeNull();
    expect(text()).toContain('não há valor anterior');
    expect(text()).toContain('SQL Injection no login');
  });

  it('DELETE: mostra só o lado antigo, sem painel de valor novo', () => {
    render(
      makeAuditLog({ action: 'DELETE', oldValue: { title: 'Removida' }, newValue: undefined }),
    );

    expect(component.kind).toBe('deleted');
    expect(find('audit-diff-deleted')).not.toBeNull();
    expect(find('audit-diff-created')).toBeNull();
    expect(text()).toContain('Últimos valores conhecidos');
    expect(text()).toContain('Removida');
  });

  it('UPDATE: destaca só os campos que mudaram e colapsa o resto', () => {
    const before = makeVulnerabilitySnapshot();
    render(
      makeAuditLog({
        action: 'UPDATE',
        oldValue: before,
        newValue: makeVulnerabilitySnapshot({ severity: 'CRITICAL' }),
      }),
    );

    const changed = find('audit-diff-changed');
    expect(changed).not.toBeNull();
    expect(changed?.querySelectorAll('li').length).toBe(1);
    expect(changed?.textContent).toContain('Severidade');
    expect(changed?.textContent).toContain('HIGH');
    expect(changed?.textContent).toContain('CRITICAL');

    // O despejo dos demais campos é justamente o que torna uma trilha ilegível.
    expect(changed?.textContent).not.toContain('SQL Injection no login');
    expect(find('audit-diff-unchanged')).toBeNull();

    const toggle = find('audit-diff-unchanged-toggle');
    expect(toggle?.textContent).toContain(`Mostrar ${Object.keys(before).length - 1}`);
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');

    toggle?.click();
    fixture.detectChanges();

    expect(find('audit-diff-unchanged')).not.toBeNull();
    expect(text()).toContain('SQL Injection no login');
    expect(find('audit-diff-unchanged-toggle')?.getAttribute('aria-expanded')).toBe('true');
  });

  it('STATUS_CHANGE: o snapshot estreito aparece inteiro como mudança', () => {
    render(
      makeAuditLog({
        action: 'STATUS_CHANGE',
        oldValue: { status: 'OPEN' },
        newValue: { status: 'RESOLVED', resolvedAt: '2026-09-17T12:00:00Z' },
      }),
    );

    const changed = find('audit-diff-changed');
    expect(changed?.querySelectorAll('li').length).toBe(2);
    expect(changed?.textContent).toContain('OPEN');
    expect(changed?.textContent).toContain('RESOLVED');
    // Chave ausente antes da mudança: exibida como vazio, não como erro.
    expect(changed?.textContent).toContain(component.emptyValue);
    expect(find('audit-diff-unchanged-toggle')).toBeNull();
  });

  it('LOGIN: nulo dos dois lados é explicado em texto, sem painel vazio', () => {
    render(makeAuditLog({ action: 'LOGIN', entityType: 'User', oldValue: undefined, newValue: undefined }));

    expect(component.kind).toBe('empty');
    const note = find('audit-diff-empty');
    expect(note).not.toBeNull();
    expect(note?.textContent).toContain('Login não altera campos');
    expect(find('audit-diff-changed')).toBeNull();
    expect(find('audit-diff-created')).toBeNull();
  });

  it('REGISTER: mesma explicação, com o rótulo da ação', () => {
    render(makeAuditLog({ action: 'REGISTER', entityType: 'Company' }));

    expect(find('audit-diff-empty')?.textContent).toContain('Cadastro não altera campos');
  });

  it('JSON truncado ou malformado cai para texto bruto em vez de quebrar a tela', () => {
    const truncated = '{"title":"Credencial no reposit';

    expect(() =>
      render(makeAuditLog({ action: 'UPDATE', oldValue: truncated, newValue: { title: 'Novo' } })),
    ).not.toThrow();

    expect(component.kind).toBe('raw');
    expect(find('audit-diff-raw-note')?.textContent).toContain('truncado');
    expect(find('audit-diff-raw-old')?.textContent).toBe(truncated);
    expect(find('audit-diff-raw-new')?.textContent).toContain('Novo');
  });

  it('não injeta marcação: o valor do usuário é sempre texto', () => {
    const payload = '<img src="x" onerror="alert(1)">';
    render(
      makeAuditLog({ action: 'UPDATE', oldValue: { title: 'antes' }, newValue: { title: payload } }),
    );

    const changed = find('audit-diff-changed');
    expect(changed?.querySelector('img')).toBeNull();
    expect(changed?.textContent).toContain(payload);
  });

  it('descreve a mudança em texto para leitores de tela', () => {
    render(
      makeAuditLog({ action: 'UPDATE', oldValue: { status: 'OPEN' }, newValue: { status: 'RESOLVED' } }),
    );

    const pair = element().querySelector('.audit-diff__pair');
    expect(pair?.getAttribute('aria-label')).toBe('Status: de OPEN para RESOLVED');
  });

  it('recolhe os campos inalterados ao trocar de linha', () => {
    render(
      makeAuditLog({
        action: 'UPDATE',
        oldValue: makeVulnerabilitySnapshot(),
        newValue: makeVulnerabilitySnapshot({ severity: 'CRITICAL' }),
      }),
    );
    component.toggleUnchanged();
    fixture.detectChanges();
    expect(component.showUnchanged).toBeTrue();

    render(
      makeAuditLog({
        id: 2,
        action: 'UPDATE',
        oldValue: makeVulnerabilitySnapshot(),
        newValue: makeVulnerabilitySnapshot({ cve: 'CVE-2026-9999' }),
      }),
    );

    expect(component.showUnchanged).toBeFalse();
  });
});
