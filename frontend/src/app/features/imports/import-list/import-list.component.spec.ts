import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { ApiError, Role } from '../../../core/models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
} from '../../../core/services/auth.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { ImportService } from '../services/import.service';
import { makeScanImportPage, makeScanImportSummary } from '../testing/import-test-utils';
import { ImportListComponent } from './import-list.component';

class ActivatedRouteStub {
  private readonly queryParams$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
  readonly queryParamMap: Observable<ParamMap> = this.queryParams$.asObservable();

  emit(params: Params): void {
    this.queryParams$.next(convertToParamMap(params));
  }
}

const DEFAULT_QUERY = { page: 0, size: 20, sort: 'createdAt,desc' };

describe('ImportListComponent', () => {
  let fixture: ComponentFixture<ImportListComponent>;
  let component: ImportListComponent;
  let importService: jasmine.SpyObj<ImportService>;
  let route: ActivatedRouteStub;
  let router: Router;

  const setup = (role: Role = 'ANALYST'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    importService = jasmine.createSpyObj<ImportService>('ImportService', [
      'list',
      'get',
      'upload',
      'mapFinding',
      'confirm',
      'discard',
    ]);
    importService.list.and.returnValue(of(makeScanImportPage([makeScanImportSummary()])));
    route = new ActivatedRouteStub();

    TestBed.configureTestingModule({
      declarations: [ImportListComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: ImportService, useValue: importService },
        { provide: ActivatedRoute, useValue: route },
      ],
    });

    fixture = TestBed.createComponent(ImportListComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const text = (): string => element().textContent ?? '';
  const rows = (): NodeListOf<Element> => element().querySelectorAll('tr[mat-row]');
  const find = (testId: string): HTMLElement | null =>
    element().querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const navigatedWith = (queryParams: Params): void =>
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams, queryParamsHandling: 'merge' }),
    );

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('carrega com os padrões do backend e renderiza as linhas', () => {
    setup();
    importService.list.and.returnValue(
      of(
        makeScanImportPage([
          makeScanImportSummary(),
          makeScanImportSummary({ id: 5, originalFilename: 'zap.json', format: 'ZAP_JSON' }),
        ]),
      ),
    );

    fixture.detectChanges();

    expect(importService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
    expect(rows().length).toBe(2);
    expect(text()).toContain('varredura.xml');
    expect(text()).toContain('OWASP ZAP (JSON)');
    expect(text()).toContain('Ana Souza');
  });

  it('mostra os contadores e a situação com ícone e texto, nunca só por cor', () => {
    setup();
    fixture.detectChanges();

    expect(text()).toContain('3 achados');
    expect(text()).toContain('1 sem ativo');
    const tag = element().querySelector('.imports-tag');
    expect(tag?.querySelector('mat-icon')).not.toBeNull();
    expect(tag?.textContent).toContain('Aguardando revisão');
  });

  it('lê página, tamanho e ordenação dos query params da URL', () => {
    setup();
    fixture.detectChanges();
    importService.list.calls.reset();

    route.emit({ page: '2', size: '50', sort: 'status,asc' });
    fixture.detectChanges();

    expect(importService.list).toHaveBeenCalledWith({ page: 2, size: 50, sort: 'status,asc' });
    expect(component.query.page).toBe(2);
    expect(component.query.size).toBe(50);
    expect(component.sortActive).toBe('status');
    expect(component.sortDirection).toBe('asc');
  });

  it('ignora página, tamanho e ordenação inválidos vindos da URL', () => {
    setup();
    fixture.detectChanges();
    importService.list.calls.reset();

    route.emit({ page: '-3', size: 'abc', sort: 'projectName,asc' });

    expect(importService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
  });

  it('limita o tamanho de página ao teto que o backend aceita', () => {
    setup();
    fixture.detectChanges();
    importService.list.calls.reset();

    route.emit({ size: '5000' });

    expect(importService.list).toHaveBeenCalledWith({ page: 0, size: 100, sort: 'createdAt,desc' });
  });

  it('paginação e ordenação navegam escrevendo os parâmetros na URL', () => {
    setup();
    fixture.detectChanges();

    component.onPage({ pageIndex: 3, pageSize: 50, length: 200, previousPageIndex: 0 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: 3, size: 50 } }),
    );

    // Voltar ao padrão limpa o parâmetro em vez de repeti-lo na URL.
    component.onPage({ pageIndex: 0, pageSize: 20, length: 200, previousPageIndex: 3 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: null, size: null } }),
    );

    component.onSort({ active: 'status', direction: 'asc' });
    navigatedWith({ sort: 'status,asc', page: null });

    component.onSort({ active: 'projectName', direction: 'asc' });
    navigatedWith({ sort: null, page: null });
  });

  it('só uma importação pendente leva à prévia', () => {
    setup();
    importService.list.and.returnValue(
      of(
        makeScanImportPage([
          makeScanImportSummary(),
          makeScanImportSummary({ id: 5, status: 'CONFIRMED', originalFilename: 'antiga.xml' }),
        ]),
      ),
    );

    fixture.detectChanges();

    const links = element().querySelectorAll<HTMLAnchorElement>('tbody a[href]');
    expect(links.length).toBe(1);
    expect(links[0].getAttribute('href')).toBe('/imports/4');
    expect(text()).toContain('antiga.xml');
  });

  it('esconde o botão de importar de quem não pode criar vulnerabilidades', () => {
    setup('VIEWER');
    fixture.detectChanges();

    expect(component.canImport).toBeFalse();
    expect(find('import-new')).toBeNull();
  });

  it('oferece o botão de importar a ADMIN e ANALYST', () => {
    setup('ADMIN');
    fixture.detectChanges();

    expect(find('import-new')).not.toBeNull();
  });

  it('mostra o estado vazio quando nada foi importado', () => {
    setup();
    importService.list.and.returnValue(of(makeScanImportPage([])));

    fixture.detectChanges();

    expect(component.state).toBe('empty');
    expect(text()).toContain('Nenhum relatório importado ainda.');
  });

  it('mostra o erro do servidor e permite tentar de novo', () => {
    setup();
    const apiError: ApiError = {
      timestamp: '2026-09-17T00:00:00Z',
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'Falha ao consultar as importações',
      path: '/api/v1/scan-imports',
      traceId: 'trace-1',
    };
    importService.list.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: apiError })),
    );

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(text()).toContain('Falha ao consultar as importações');

    importService.list.and.returnValue(of(makeScanImportPage([makeScanImportSummary()])));
    element().querySelector<HTMLButtonElement>('.sh-state button')?.click();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(rows().length).toBe(1);
  });

  it('encerra a assinatura dos query params ao destruir', () => {
    setup();
    fixture.detectChanges();
    importService.list.calls.reset();

    fixture.destroy();
    route.emit({ page: '1' });

    expect(importService.list).not.toHaveBeenCalled();
  });

  it('a tabela, a legenda e a paginação são anunciadas para leitores de tela', () => {
    setup();
    fixture.detectChanges();

    const table = element().querySelector('table');
    expect(table?.getAttribute('aria-label')).toBe('Histórico de importações');
    expect(table?.querySelector('caption')?.classList).toContain('sh-visually-hidden');
    expect(element().querySelectorAll('th[scope="col"]').length).toBe(
      component.displayedColumns.length,
    );
    expect(element().querySelector('mat-paginator')?.getAttribute('aria-label')).toBe(
      'Paginação do histórico de importações',
    );
  });
});
