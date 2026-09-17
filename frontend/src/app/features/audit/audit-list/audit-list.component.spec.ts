import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { ApiError } from '../../../core/models';
import { SharedModule } from '../../../shared/shared.module';
import { AuditDiffComponent } from '../audit-diff/audit-diff.component';
import { AuditActorService } from '../services/audit-actor.service';
import { AuditService } from '../services/audit.service';
import {
  makeAuditActor,
  makeAuditLog,
  makeAuditPage,
  makeVulnerabilitySnapshot,
} from '../testing/audit-test-utils';
import { toCivilDate } from '../utils/audit-date.util';
import { AuditListComponent } from './audit-list.component';

class ActivatedRouteStub {
  private readonly queryParams$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
  readonly queryParamMap: Observable<ParamMap> = this.queryParams$.asObservable();

  emit(params: Params): void {
    this.queryParams$.next(convertToParamMap(params));
  }
}

const DEFAULT_QUERY = {
  page: 0,
  size: 20,
  sort: 'createdAt,desc',
  entityType: undefined,
  actorId: undefined,
  action: undefined,
  from: undefined,
  to: undefined,
};

describe('AuditListComponent', () => {
  let fixture: ComponentFixture<AuditListComponent>;
  let component: AuditListComponent;
  let auditService: jasmine.SpyObj<AuditService>;
  let actorService: jasmine.SpyObj<AuditActorService>;
  let route: ActivatedRouteStub;
  let router: Router;

  const setup = (): void => {
    auditService = jasmine.createSpyObj<AuditService>('AuditService', ['list']);
    auditService.list.and.returnValue(of(makeAuditPage([makeAuditLog()])));
    actorService = jasmine.createSpyObj<AuditActorService>('AuditActorService', ['list']);
    actorService.list.and.returnValue(of([makeAuditActor()]));
    route = new ActivatedRouteStub();

    TestBed.configureTestingModule({
      declarations: [AuditListComponent, AuditDiffComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: AuditService, useValue: auditService },
        { provide: AuditActorService, useValue: actorService },
        { provide: ActivatedRoute, useValue: route },
      ],
    });

    fixture = TestBed.createComponent(AuditListComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => element().textContent ?? '';
  /** Só as linhas de dados: a linha de detalhe existe sempre, colapsada. */
  const rows = (): NodeListOf<Element> =>
    element().querySelectorAll('tr[mat-row]:not(.audit-table__detail-row)');
  const find = (testId: string): HTMLElement | null =>
    element().querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const navigatedWith = (queryParams: Params): void =>
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams, queryParamsHandling: 'merge' }),
    );

  beforeEach(() => setup());

  afterEach(() => TestBed.resetTestingModule());

  it('carrega com os padrões do backend e renderiza as linhas', () => {
    auditService.list.and.returnValue(
      of(
        makeAuditPage([
          makeAuditLog(),
          makeAuditLog({ id: 2, action: 'DELETE', entityType: 'Asset', entityId: 9 }),
        ]),
      ),
    );

    fixture.detectChanges();

    expect(auditService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
    expect(rows().length).toBe(2);
    expect(text()).toContain('ana@empresa.com');
    expect(text()).toContain('Vulnerabilidade');
    expect(text()).toContain('Ativo');
  });

  it('carrega as opções do filtro por ator', () => {
    fixture.detectChanges();

    expect(actorService.list).toHaveBeenCalled();
    expect(component.actors.length).toBe(1);
  });

  it('mostra a ação com ícone e texto, nunca só por cor', () => {
    fixture.detectChanges();

    const chip = element().querySelector('.audit-chip');
    expect(chip?.querySelector('mat-icon')).not.toBeNull();
    expect(chip?.textContent).toContain('Alteração');
  });

  it('identifica como Sistema a linha sem ator', () => {
    auditService.list.and.returnValue(
      of(
        makeAuditPage([
          makeAuditLog({ action: 'LOGIN_FAILED', actorId: undefined, actorEmail: undefined }),
        ]),
      ),
    );

    fixture.detectChanges();

    expect(text()).toContain('Sistema');
  });

  it('não oferece nenhuma ação de escrita: a trilha é somente leitura', () => {
    fixture.detectChanges();

    // O único controle das linhas é o que revela a comparação; não há link de edição
    // nem botão de exclusão. (O ícone `edit` aparece no chip da ação ALTERAÇÃO, que é
    // rótulo do que aconteceu, não uma affordance.)
    const controls = element().querySelectorAll('table tbody a, table tbody button');
    expect(controls.length).toBe(1);
    expect(controls[0].getAttribute('data-testid')).toBe('audit-expand');
    expect(element().querySelectorAll('a[href*="editar"]').length).toBe(0);

    const api = component as unknown as Record<string, unknown>;
    for (const method of ['delete', 'confirmDelete', 'edit', 'save', 'update']) {
      expect(api[method]).withContext(method).toBeUndefined();
    }
  });

  it('restaura página, tamanho, ordenação e todos os filtros dos query params', () => {
    fixture.detectChanges();
    auditService.list.calls.reset();

    route.emit({
      page: '2',
      size: '50',
      sort: 'action,asc',
      entityType: 'Vulnerability',
      actorId: '7',
      action: 'STATUS_CHANGE',
      from: '2026-09-01',
      to: '2026-09-17',
    });
    fixture.detectChanges();

    expect(auditService.list).toHaveBeenCalledWith({
      page: 2,
      size: 50,
      sort: 'action,asc',
      entityType: 'Vulnerability',
      actorId: 7,
      action: 'STATUS_CHANGE',
      from: '2026-09-01',
      to: '2026-09-17',
    });
    expect(component.entityTypeControl.value).toBe('Vulnerability');
    expect(component.actorControl.value).toBe(7);
    expect(component.actionControl.value).toBe('STATUS_CHANGE');
    expect(toCivilDate(component.fromControl.value as Date)).toBe('2026-09-01');
    expect(toCivilDate(component.toControl.value as Date)).toBe('2026-09-17');
    expect(component.sortActive).toBe('action');
    expect(component.sortDirection).toBe('asc');
  });

  it('ignora sort, ação, entidade, ator e datas inválidos vindos da URL', () => {
    fixture.detectChanges();
    auditService.list.calls.reset();

    route.emit({
      sort: 'ipAddress,asc',
      action: 'APAGAR_TUDO',
      entityType: 'Segredo',
      actorId: 'abc',
      from: '2026-02-31',
      to: '17/09/2026',
      page: '-3',
    });

    expect(auditService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
  });

  it('cada filtro navega mesclando os query params e volta para a primeira página', () => {
    fixture.detectChanges();

    component.onEntityTypeChange('Asset');
    navigatedWith({ entityType: 'Asset', page: null });

    component.onActorChange(7);
    navigatedWith({ actorId: 7, page: null });

    component.onActionChange('DELETE');
    navigatedWith({ action: 'DELETE', page: null });

    component.onFromChange(new Date(2026, 8, 1));
    navigatedWith({ from: '2026-09-01', page: null });

    component.onToChange(new Date(2026, 8, 17));
    navigatedWith({ to: '2026-09-17', page: null });

    component.onEntityTypeChange('');
    navigatedWith({ entityType: null, page: null });

    component.onFromChange(null);
    navigatedWith({ from: null, page: null });
  });

  it('a data escolhida vira o dia civil do usuário, não o dia em UTC', () => {
    fixture.detectChanges();

    // 21h em UTC-3 já seria 18/09 em UTC; o filtro tem de guardar o dia marcado.
    component.onFromChange(new Date(2026, 8, 17, 21, 30));

    navigatedWith({ from: '2026-09-17', page: null });
  });

  it('limpar filtros remove todos os parâmetros de filtro da URL', () => {
    fixture.detectChanges();

    component.clearFilters();

    navigatedWith({
      entityType: null,
      actorId: null,
      action: null,
      from: null,
      to: null,
      page: null,
    });
  });

  it('paginação e ordenação navegam com os parâmetros da API', () => {
    fixture.detectChanges();

    component.onPage({ pageIndex: 3, pageSize: 50, length: 200, previousPageIndex: 0 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: 3, size: 50 } }),
    );

    component.onPage({ pageIndex: 0, pageSize: 20, length: 200, previousPageIndex: 3 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: null, size: null } }),
    );

    component.onSort({ active: 'entityType', direction: 'asc' });
    navigatedWith({ sort: 'entityType,asc', page: null });

    // Fora da whitelist de `AuditQueryService`: volta ao padrão em vez de viajar na URL.
    component.onSort({ active: 'ipAddress', direction: 'asc' });
    navigatedWith({ sort: null, page: null });

    component.onSort({ active: 'createdAt', direction: '' });
    navigatedWith({ sort: null, page: null });
  });

  it('distingue trilha vazia de filtro sem resultado', () => {
    auditService.list.and.returnValue(of(makeAuditPage([])));

    fixture.detectChanges();
    expect(text()).toContain('Nenhum registro de auditoria ainda.');

    route.emit({ action: 'DELETE' });
    fixture.detectChanges();
    expect(text()).toContain('Nenhum registro de auditoria para os filtros aplicados.');
  });

  it('avisa quando o intervalo de datas está invertido', () => {
    fixture.detectChanges();

    route.emit({ from: '2026-09-17', to: '2026-09-01' });
    fixture.detectChanges();

    expect(component.invalidRange).toBeTrue();
    expect(find('audit-invalid-range')).not.toBeNull();
  });

  it('mostra o estado de erro com a mensagem do servidor e permite tentar de novo', () => {
    const forbidden: ApiError = {
      timestamp: '2026-09-17T00:00:00Z',
      status: 403,
      code: 'FORBIDDEN',
      message: 'Acesso restrito a administradores',
      path: '/api/v1/audit-logs',
      traceId: 'trace',
    };
    auditService.list.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403, error: forbidden })),
    );

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(text()).toContain('Acesso restrito a administradores');

    auditService.list.and.returnValue(of(makeAuditPage([makeAuditLog()])));
    element().querySelector<HTMLButtonElement>('.sh-state button')?.click();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(component.loadError).toBeNull();
    expect(rows().length).toBe(1);
  });

  it('cai na mensagem padrão quando o erro não traz envelope da API', () => {
    auditService.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    fixture.detectChanges();

    expect(component.loadError).toBeNull();
    expect(text()).toContain('Não foi possível carregar os dados.');
  });

  it('revela a comparação da linha sob demanda, com aria-expanded e aria-controls', () => {
    auditService.list.and.returnValue(
      of(
        makeAuditPage([
          makeAuditLog({
            action: 'UPDATE',
            oldValue: makeVulnerabilitySnapshot(),
            newValue: makeVulnerabilitySnapshot({ severity: 'CRITICAL' }),
          }),
        ]),
      ),
    );
    fixture.detectChanges();

    const toggle = find('audit-expand');
    expect(toggle?.getAttribute('aria-expanded')).toBe('false');
    expect(toggle?.getAttribute('aria-controls')).toBe('audit-detail-1');
    expect(find('audit-diff')).toBeNull();

    toggle?.click();
    fixture.detectChanges();

    expect(find('audit-expand')?.getAttribute('aria-expanded')).toBe('true');
    expect(element().querySelector('#audit-detail-1')).not.toBeNull();
    // Só a severidade mudou: as outras dez chaves ficam fora do destaque.
    const changed = find('audit-diff-changed');
    expect(changed?.querySelectorAll('li').length).toBe(1);
    expect(changed?.textContent).toContain('Severidade');

    find('audit-expand')?.click();
    fixture.detectChanges();
    expect(find('audit-diff')).toBeNull();
  });

  it('a comparação de uma linha com JSON truncado não derruba a tela', () => {
    auditService.list.and.returnValue(
      of(
        makeAuditPage([
          makeAuditLog({ action: 'UPDATE', oldValue: '{"title":"corta', newValue: { title: 'ok' } }),
        ]),
      ),
    );
    fixture.detectChanges();

    expect(() => {
      find('audit-expand')?.click();
      fixture.detectChanges();
    }).not.toThrow();

    expect(find('audit-diff-raw-old')?.textContent).toBe('{"title":"corta');
    expect(rows().length).toBe(1);
  });

  it('LOGIN aparece com a explicação de que não há campos a comparar', () => {
    auditService.list.and.returnValue(
      of(makeAuditPage([makeAuditLog({ action: 'LOGIN', entityType: 'User' })])),
    );
    fixture.detectChanges();

    find('audit-expand')?.click();
    fixture.detectChanges();

    expect(find('audit-diff-empty')?.textContent).toContain('não altera campos');
  });

  it('recolhe os detalhes abertos quando a consulta muda', () => {
    fixture.detectChanges();
    find('audit-expand')?.click();
    fixture.detectChanges();
    expect(find('audit-diff')).not.toBeNull();

    route.emit({ page: '1' });
    fixture.detectChanges();

    expect(find('audit-diff')).toBeNull();
  });

  it('a tabela, a legenda e a paginação são anunciadas para leitores de tela', () => {
    fixture.detectChanges();

    const table = element().querySelector('table');
    expect(table?.getAttribute('aria-label')).toBe('Trilha de auditoria');
    expect(table?.querySelector('caption')?.classList).toContain('sh-visually-hidden');
    expect(element().querySelectorAll('th[scope="col"]').length).toBe(
      component.displayedColumns.length,
    );
    expect(
      element().querySelector('mat-paginator')?.getAttribute('aria-label'),
    ).toBe('Paginação da trilha de auditoria');
  });
});
