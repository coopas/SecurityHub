import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { BehaviorSubject, Observable, of, throwError } from 'rxjs';

import { ApiError, Role } from '../../../core/models';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../../core/services/auth.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { ProjectService } from '../../projects/services/project.service';
import { makeProject, makeProjectPage } from '../../projects/testing/project-test-utils';
import { makeAsset, makeAssetPage } from '../testing/asset-test-utils';
import { AssetService } from '../services/asset.service';
import { SEARCH_DEBOUNCE_MS, AssetListComponent } from './asset-list.component';

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
  search: undefined,
  projectId: undefined,
  type: undefined,
  environment: undefined,
  criticality: undefined,
};

describe('AssetListComponent', () => {
  let fixture: ComponentFixture<AssetListComponent>;
  let component: AssetListComponent;
  let assetService: jasmine.SpyObj<AssetService>;
  let projectService: jasmine.SpyObj<ProjectService>;
  let route: ActivatedRouteStub;
  let router: Router;
  let dialog: jasmine.SpyObj<MatDialog>;

  const setup = (role: Role = 'ADMIN'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    assetService = jasmine.createSpyObj<AssetService>('AssetService', ['list', 'delete']);
    assetService.list.and.returnValue(of(makeAssetPage([makeAsset()])));
    projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['list']);
    projectService.list.and.returnValue(
      of(makeProjectPage([makeProject({ id: 3, name: 'Portal do cliente' })])),
    );
    route = new ActivatedRouteStub();
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      declarations: [AssetListComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: AssetService, useValue: assetService },
        { provide: ProjectService, useValue: projectService },
        { provide: ActivatedRoute, useValue: route },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(AssetListComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  const rows = (): NodeListOf<Element> =>
    (fixture.nativeElement as HTMLElement).querySelectorAll('tr[mat-row]');

  const text = (): string => (fixture.nativeElement as HTMLElement).textContent ?? '';

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('carrega com os padrões e renderiza as linhas', () => {
    setup();
    assetService.list.and.returnValue(
      of(makeAssetPage([makeAsset(), makeAsset({ id: 2, name: 'Banco de dados de cobrança' })])),
    );

    fixture.detectChanges();

    expect(assetService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
    expect(rows().length).toBe(2);
    expect(text()).toContain('Banco de dados de cobrança');
    expect(text()).toContain('Portal do cliente');
  });

  it('mostra criticidade e ambiente com ícone e texto, nunca só por cor', () => {
    setup();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const criticality = element.querySelector('.assets-criticality');
    expect(criticality?.querySelector('mat-icon')).not.toBeNull();
    expect(criticality?.textContent).toContain('Alta');
    expect(text()).toContain('Produção');
  });

  it('carrega as opções do filtro por projeto', () => {
    setup();
    fixture.detectChanges();

    expect(projectService.list).toHaveBeenCalledWith({ page: 0, size: 100, sort: 'name,asc' });
    expect(component.projects).toEqual([{ id: 3, name: 'Portal do cliente' }]);
  });

  it('restaura página, tamanho, ordenação e filtros dos query params', () => {
    setup();
    fixture.detectChanges();
    assetService.list.calls.reset();

    route.emit({
      page: '2',
      size: '50',
      sort: 'name,asc',
      search: 'pagamentos',
      projectId: '3',
      type: 'API',
      environment: 'PRODUCTION',
      criticality: 'CRITICAL',
    });
    fixture.detectChanges();

    expect(assetService.list).toHaveBeenCalledWith({
      page: 2,
      size: 50,
      sort: 'name,asc',
      search: 'pagamentos',
      projectId: 3,
      type: 'API',
      environment: 'PRODUCTION',
      criticality: 'CRITICAL',
    });
    expect(component.searchControl.value).toBe('pagamentos');
    expect(component.projectControl.value).toBe(3);
    expect(component.typeControl.value).toBe('API');
    expect(component.environmentControl.value).toBe('PRODUCTION');
    expect(component.criticalityControl.value).toBe('CRITICAL');
    expect(component.sortActive).toBe('name');
    expect(component.sortDirection).toBe('asc');
  });

  it('ignora sort, enums e projectId inválidos vindos da URL', () => {
    setup();
    fixture.detectChanges();
    assetService.list.calls.reset();

    route.emit({
      sort: 'senha,asc',
      type: 'QUALQUER',
      environment: 'NUVEM',
      criticality: 'URGENTE',
      projectId: 'abc',
      page: '-3',
    });

    expect(assetService.list).toHaveBeenCalledWith(DEFAULT_QUERY);
  });

  it('distingue lista vazia de filtro sem resultado', () => {
    setup();
    assetService.list.and.returnValue(of(makeAssetPage([])));

    fixture.detectChanges();
    expect(text()).toContain('Nenhum ativo cadastrado ainda.');

    route.emit({ criticality: 'CRITICAL' });
    fixture.detectChanges();
    expect(text()).toContain('Nenhum ativo encontrado para os filtros aplicados.');
  });

  it('mostra o estado de erro com retry', () => {
    setup();
    assetService.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(text()).toContain('Não foi possível carregar os dados.');

    assetService.list.and.returnValue(of(makeAssetPage([makeAsset()])));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.sh-state button')?.click();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(rows().length).toBe(1);
  });

  it('a busca navega com debounce de 350ms', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    component.searchControl.setValue('pagamentos');
    tick(349);
    expect(router.navigate).not.toHaveBeenCalled();

    tick(1);
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: { search: 'pagamentos', page: null },
        queryParamsHandling: 'merge',
      }),
    );
  }));

  it('reaplica o mesmo termo depois de limpar os filtros', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    component.searchControl.setValue('pagamentos');
    tick(SEARCH_DEBOUNCE_MS);
    expect(router.navigate).toHaveBeenCalledTimes(1);

    // A navegação real devolveria o termo pela rota; o stub reproduz esse passo.
    route.emit({ search: 'pagamentos' });
    fixture.detectChanges();

    // "Limpar filtros" volta a rota ao estado sem parâmetros.
    route.emit({});
    fixture.detectChanges();
    expect(component.searchControl.value).toBe('');

    component.searchControl.setValue('pagamentos');
    tick(SEARCH_DEBOUNCE_MS);

    expect(router.navigate).toHaveBeenCalledTimes(2);
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: { search: 'pagamentos', page: null },
        queryParamsHandling: 'merge',
      }),
    );
  }));

  it('não navega quando o termo digitado é o que já está aplicado', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    route.emit({ search: 'pagamentos' });
    fixture.detectChanges();
    (router.navigate as jasmine.Spy).calls.reset();

    component.searchControl.setValue('pagamentos');
    tick(SEARCH_DEBOUNCE_MS);

    expect(router.navigate).not.toHaveBeenCalled();
  }));

  it('cada filtro navega mesclando os query params e volta para a primeira página', () => {
    setup();
    fixture.detectChanges();

    component.onProjectChange(3);
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: { projectId: 3, page: null },
        queryParamsHandling: 'merge',
      }),
    );

    component.onTypeChange('SERVER');
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { type: 'SERVER', page: null } }),
    );

    component.onEnvironmentChange('STAGING');
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { environment: 'STAGING', page: null } }),
    );

    component.onCriticalityChange('CRITICAL');
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { criticality: 'CRITICAL', page: null } }),
    );

    component.onTypeChange('');
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { type: null, page: null } }),
    );
  });

  it('limpar filtros remove todos os parâmetros de filtro da URL', () => {
    setup();
    fixture.detectChanges();

    component.clearFilters();

    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: {
          search: null,
          projectId: null,
          type: null,
          environment: null,
          criticality: null,
          page: null,
        },
        queryParamsHandling: 'merge',
      }),
    );
  });

  it('paginação e ordenação navegam com os parâmetros da API', () => {
    setup();
    fixture.detectChanges();

    component.onPage({ pageIndex: 3, pageSize: 50, length: 200, previousPageIndex: 0 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: 3, size: 50 } }),
    );

    component.onSort({ active: 'criticality', direction: 'asc' });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { sort: 'criticality,asc', page: null } }),
    );

    // Fora da whitelist do backend: volta ao padrão em vez de viajar na URL.
    component.onSort({ active: 'projectName', direction: 'asc' });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { sort: null, page: null } }),
    );

    component.onSort({ active: 'name', direction: '' });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { sort: null, page: null } }),
    );
  });

  it('exclui após confirmação e mostra a mensagem do servidor em 409', () => {
    setup();
    fixture.detectChanges();

    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, boolean>);
    const conflict: ApiError = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 409,
      code: 'CONFLICT',
      message: 'O ativo possui vulnerabilidades vinculadas',
      path: '/api/v1/assets/1',
      traceId: 'trace',
    };
    assetService.delete.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: conflict })),
    );

    component.confirmDelete(makeAsset());
    fixture.detectChanges();

    expect(assetService.delete).toHaveBeenCalledWith(1);
    expect(component.actionError).toBe('O ativo possui vulnerabilidades vinculadas');
    expect(text()).toContain('O ativo possui vulnerabilidades vinculadas');
  });

  it('não exclui quando a confirmação é cancelada', () => {
    setup();
    fixture.detectChanges();

    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown, boolean>);
    component.confirmDelete(makeAsset());

    expect(assetService.delete).not.toHaveBeenCalled();
  });

  it('esconde as ações de escrita para VIEWER', () => {
    setup('VIEWER');
    fixture.detectChanges();

    expect(component.isAdmin).toBeFalse();
    expect(component.displayedColumns).toEqual([
      'name',
      'type',
      'environment',
      'criticality',
      'project',
      'vulnerabilityCount',
      'createdAt',
    ]);

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="asset-create"]')).toBeNull();
    expect(element.querySelector('[aria-label^="Excluir ativo"]')).toBeNull();
    expect(element.querySelector('[aria-label^="Editar ativo"]')).toBeNull();
  });

  it('mostra as ações de escrita para ADMIN', () => {
    setup('ADMIN');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(component.displayedColumns).toContain('actions');
    expect(element.querySelector('[data-testid="asset-create"]')).not.toBeNull();
    expect(element.querySelector('[aria-label^="Excluir ativo"]')).not.toBeNull();
  });
});
