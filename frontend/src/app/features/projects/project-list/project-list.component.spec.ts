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
import { makeProject, makeProjectPage } from '../testing/project-test-utils';
import { ProjectService } from '../services/project.service';
import { SEARCH_DEBOUNCE_MS, ProjectListComponent } from './project-list.component';

class ActivatedRouteStub {
  private readonly queryParams$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
  readonly queryParamMap: Observable<ParamMap> = this.queryParams$.asObservable();

  emit(params: Params): void {
    this.queryParams$.next(convertToParamMap(params));
  }
}

describe('ProjectListComponent', () => {
  let fixture: ComponentFixture<ProjectListComponent>;
  let component: ProjectListComponent;
  let projectService: jasmine.SpyObj<ProjectService>;
  let route: ActivatedRouteStub;
  let router: Router;
  let dialog: jasmine.SpyObj<MatDialog>;

  const setup = (role: Role = 'ADMIN'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['list', 'delete']);
    projectService.list.and.returnValue(of(makeProjectPage([makeProject()])));
    route = new ActivatedRouteStub();
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      declarations: [ProjectListComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: ProjectService, useValue: projectService },
        { provide: ActivatedRoute, useValue: route },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(ProjectListComponent);
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
    projectService.list.and.returnValue(
      of(makeProjectPage([makeProject(), makeProject({ id: 2, name: 'API de pagamentos' })])),
    );

    fixture.detectChanges();

    expect(projectService.list).toHaveBeenCalledWith({
      page: 0,
      size: 20,
      sort: 'createdAt,desc',
      search: undefined,
      status: undefined,
    });
    expect(rows().length).toBe(2);
    expect(text()).toContain('API de pagamentos');
    expect(text()).toContain('Ativo');
  });

  it('restaura página, tamanho, ordenação e filtros dos query params', () => {
    setup();
    fixture.detectChanges();
    projectService.list.calls.reset();

    route.emit({ page: '2', size: '50', sort: 'name,asc', search: 'portal', status: 'ARCHIVED' });
    fixture.detectChanges();

    expect(projectService.list).toHaveBeenCalledWith({
      page: 2,
      size: 50,
      sort: 'name,asc',
      search: 'portal',
      status: 'ARCHIVED',
    });
    expect(component.searchControl.value).toBe('portal');
    expect(component.statusControl.value).toBe('ARCHIVED');
    expect(component.sortActive).toBe('name');
    expect(component.sortDirection).toBe('asc');
  });

  it('ignora sort e status inválidos vindos da URL', () => {
    setup();
    fixture.detectChanges();
    projectService.list.calls.reset();

    route.emit({ sort: 'senha,asc', status: 'QUALQUER', page: '-3' });

    expect(projectService.list).toHaveBeenCalledWith({
      page: 0,
      size: 20,
      sort: 'createdAt,desc',
      search: undefined,
      status: undefined,
    });
  });

  it('distingue lista vazia de filtro sem resultado', () => {
    setup();
    projectService.list.and.returnValue(of(makeProjectPage([])));

    fixture.detectChanges();
    expect(text()).toContain('Nenhum projeto cadastrado ainda.');

    route.emit({ search: 'inexistente' });
    fixture.detectChanges();
    expect(text()).toContain('Nenhum projeto encontrado para os filtros aplicados.');
  });

  it('mostra o estado de erro com retry', () => {
    setup();
    projectService.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(text()).toContain('Não foi possível carregar os dados.');

    projectService.list.and.returnValue(of(makeProjectPage([makeProject()])));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.sh-state button')?.click();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(rows().length).toBe(1);
  });

  it('a busca navega com debounce de 350ms', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    component.searchControl.setValue('portal');
    tick(349);
    expect(router.navigate).not.toHaveBeenCalled();

    tick(1);
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: { search: 'portal', page: null },
        queryParamsHandling: 'merge',
      }),
    );
  }));

  it('reaplica o mesmo termo depois de limpar os filtros', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    component.searchControl.setValue('portal');
    tick(SEARCH_DEBOUNCE_MS);
    expect(router.navigate).toHaveBeenCalledTimes(1);

    // A navegação real devolveria o termo pela rota; o stub reproduz esse passo.
    route.emit({ search: 'portal' });
    fixture.detectChanges();

    // "Limpar filtros" volta a rota ao estado sem parâmetros.
    route.emit({});
    fixture.detectChanges();
    expect(component.searchControl.value).toBe('');

    component.searchControl.setValue('portal');
    tick(SEARCH_DEBOUNCE_MS);

    expect(router.navigate).toHaveBeenCalledTimes(2);
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({
        queryParams: { search: 'portal', page: null },
        queryParamsHandling: 'merge',
      }),
    );
  }));

  it('não navega quando o termo digitado é o que já está aplicado', fakeAsync(() => {
    setup();
    fixture.detectChanges();

    route.emit({ search: 'portal' });
    fixture.detectChanges();
    (router.navigate as jasmine.Spy).calls.reset();

    component.searchControl.setValue('portal');
    tick(SEARCH_DEBOUNCE_MS);

    expect(router.navigate).not.toHaveBeenCalled();
  }));

  it('paginação e ordenação navegam com os parâmetros da API', () => {
    setup();
    fixture.detectChanges();

    component.onPage({ pageIndex: 3, pageSize: 50, length: 200, previousPageIndex: 0 });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { page: 3, size: 50 } }),
    );

    component.onSort({ active: 'name', direction: 'asc' });
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: { sort: 'name,asc', page: null } }),
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
      message: 'O projeto possui ativos vinculados',
      path: '/api/v1/projects/1',
      traceId: 'trace',
    };
    projectService.delete.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: conflict })),
    );

    component.confirmDelete(makeProject());
    fixture.detectChanges();

    expect(projectService.delete).toHaveBeenCalledWith(1);
    expect(component.actionError).toBe('O projeto possui ativos vinculados');
    expect(text()).toContain('O projeto possui ativos vinculados');
  });

  it('não exclui quando a confirmação é cancelada', () => {
    setup();
    fixture.detectChanges();

    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown, boolean>);
    component.confirmDelete(makeProject());

    expect(projectService.delete).not.toHaveBeenCalled();
  });

  it('esconde as ações de escrita para VIEWER', () => {
    setup('VIEWER');
    fixture.detectChanges();

    expect(component.isAdmin).toBeFalse();
    expect(component.displayedColumns).toEqual(['name', 'status', 'assetCount', 'createdAt']);

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="project-create"]')).toBeNull();
    expect(element.querySelector('[aria-label^="Excluir projeto"]')).toBeNull();
    expect(element.querySelector('[aria-label^="Editar projeto"]')).toBeNull();
  });

  it('mostra as ações de escrita para ADMIN', () => {
    setup('ADMIN');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="project-create"]')).not.toBeNull();
    expect(element.querySelector('[aria-label^="Excluir projeto"]')).not.toBeNull();
  });
});
