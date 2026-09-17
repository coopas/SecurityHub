import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of, throwError } from 'rxjs';

import { ApiError } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { SharedModule } from '../../../shared/shared.module';
import { ProjectService } from '../../projects/services/project.service';
import { makeProject, makeProjectPage } from '../../projects/testing/project-test-utils';
import { makeAsset } from '../testing/asset-test-utils';
import { AssetService } from '../services/asset.service';
import { AssetFormComponent } from './asset-form.component';

const apiError = (
  status: number,
  code: ApiError['code'],
  message: string,
  fieldErrors?: ApiError['fieldErrors'],
): HttpErrorResponse =>
  new HttpErrorResponse({
    status,
    error: {
      timestamp: '2026-01-01T00:00:00Z',
      status,
      code,
      message,
      path: '/api/v1/assets',
      fieldErrors,
      traceId: 'trace',
    } as ApiError,
  });

describe('AssetFormComponent', () => {
  let fixture: ComponentFixture<AssetFormComponent>;
  let component: AssetFormComponent;
  let assetService: jasmine.SpyObj<AssetService>;
  let projectService: jasmine.SpyObj<ProjectService>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let router: Router;

  const setup = (id?: string): void => {
    assetService = jasmine.createSpyObj<AssetService>('AssetService', ['get', 'create', 'update']);
    assetService.get.and.returnValue(
      of(makeAsset({ id: 7, name: 'API de pagamentos', projectId: 3, projectName: 'Portal do cliente' })),
    );
    assetService.create.and.returnValue(of(makeAsset({ id: 12 })));
    assetService.update.and.returnValue(of(makeAsset({ id: 7 })));
    projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['list']);
    projectService.list.and.returnValue(
      of(makeProjectPage([makeProject({ id: 3, name: 'Portal do cliente' })])),
    );
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);

    TestBed.configureTestingModule({
      declarations: [AssetFormComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: AssetService, useValue: assetService },
        { provide: ProjectService, useValue: projectService },
        { provide: NotificationService, useValue: notifications },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(id ? { id } : {}) } },
        },
      ],
    });

    fixture = TestBed.createComponent(AssetFormComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  afterEach(() => TestBed.resetTestingModule());

  it('carrega as opções de projeto antes de exibir o formulário', () => {
    setup();
    fixture.detectChanges();

    expect(projectService.list).toHaveBeenCalledWith({ page: 0, size: 100, sort: 'name,asc' });
    expect(component.loadState).toBeNull();
    expect(component.projects).toEqual([{ id: 3, name: 'Portal do cliente' }]);
    expect((fixture.nativeElement as HTMLElement).querySelector('form')).not.toBeNull();
  });

  it('explica que é preciso cadastrar um projeto quando a empresa não tem nenhum', () => {
    setup();
    projectService.list.and.returnValue(of(makeProjectPage([])));

    fixture.detectChanges();

    expect(component.hasNoProjects).toBeTrue();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="asset-no-projects"]')).not.toBeNull();
    expect(element.querySelector('form')).toBeNull();
    expect(element.textContent).toContain('Todo ativo pertence a um projeto');
  });

  it('não envia formulário inválido e marca os campos', () => {
    setup();
    fixture.detectChanges();

    component.submit();

    expect(assetService.create).not.toHaveBeenCalled();
    expect(component.form.controls['name'].touched).toBeTrue();
    expect(component.form.controls['projectId'].touched).toBeTrue();
  });

  it('cria, avisa e navega para o detalhe', () => {
    setup();
    fixture.detectChanges();

    component.form.patchValue({
      projectId: 3,
      name: '  Servidor de borda  ',
      type: 'SERVER',
      identifier: '  ',
      environment: 'STAGING',
      criticality: 'LOW',
      description: '  ',
    });
    component.submit();

    expect(assetService.create).toHaveBeenCalledWith({
      projectId: 3,
      name: 'Servidor de borda',
      description: undefined,
      type: 'SERVER',
      identifier: undefined,
      environment: 'STAGING',
      criticality: 'LOW',
    });
    expect(notifications.success).toHaveBeenCalledWith('Ativo criado.');
    expect(router.navigate).toHaveBeenCalledWith(['/assets', 12]);
    expect(component.submitting).toBeFalse();
  });

  it('carrega o ativo e atualiza no modo edição', () => {
    setup('7');
    fixture.detectChanges();

    expect(assetService.get).toHaveBeenCalledWith(7);
    expect(component.isEdit).toBeTrue();
    expect(component.form.value.name).toBe('API de pagamentos');
    expect(component.form.value.projectId).toBe(3);
    expect(component.form.value.identifier).toBe('api.pagamentos.local');

    component.form.patchValue({ name: 'API de cobranças' });
    component.submit();

    expect(assetService.update).toHaveBeenCalledWith(7, {
      projectId: 3,
      name: 'API de cobranças',
      description: 'Serviço de cobrança',
      type: 'API',
      identifier: 'api.pagamentos.local',
      environment: 'PRODUCTION',
      criticality: 'HIGH',
    });
    expect(notifications.success).toHaveBeenCalledWith('Ativo atualizado.');
  });

  it('acrescenta o projeto do ativo às opções quando ele não vem na página carregada', () => {
    setup('7');
    projectService.list.and.returnValue(
      of(makeProjectPage([makeProject({ id: 99, name: 'Outro projeto' })])),
    );

    fixture.detectChanges();

    expect(component.projects).toEqual([
      { id: 3, name: 'Portal do cliente' },
      { id: 99, name: 'Outro projeto' },
    ]);
  });

  it('mapeia fieldErrors de 400 nos controles', () => {
    setup();
    fixture.detectChanges();

    assetService.create.and.returnValue(
      throwError(() =>
        apiError(400, 'VALIDATION_ERROR', 'Dados inválidos', [
          { field: 'name', message: 'deve ter entre 2 e 140 caracteres' },
          { field: 'projectId', message: 'é obrigatório' },
        ]),
      ),
    );

    component.form.patchValue({ projectId: 3, name: 'Ativo' });
    component.submit();
    fixture.detectChanges();

    expect(component.form.controls['name'].getError('server')).toBe('deve ter entre 2 e 140 caracteres');
    expect(component.form.controls['projectId'].getError('server')).toBe('é obrigatório');
    expect(component.generalError).toBeNull();
    expect(component.submitting).toBeFalse();
  });

  it('exibe a mensagem do servidor em 409 de identificador duplicado', () => {
    setup();
    fixture.detectChanges();

    assetService.create.and.returnValue(
      throwError(() =>
        apiError(409, 'CONFLICT', 'Já existe um ativo com esse identificador neste projeto'),
      ),
    );

    component.form.patchValue({ projectId: 3, name: 'API', identifier: 'api.local' });
    component.submit();
    fixture.detectChanges();

    expect(component.generalError).toBe('Já existe um ativo com esse identificador neste projeto');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Já existe um ativo com esse identificador neste projeto',
    );
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('trata 404 na edição como ativo não encontrado, sem retry', () => {
    setup('99');
    assetService.get.and.returnValue(throwError(() => apiError(404, 'NOT_FOUND', 'Ativo 99 não encontrado')));

    fixture.detectChanges();

    expect(component.loadState).toBe('error');
    expect(component.notFound).toBeTrue();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Ativo não encontrado.');
    expect(element.querySelector('.sh-state button')).toBeNull();
  });

  it('permite tentar de novo quando o carregamento falha por erro do servidor', () => {
    setup('7');
    assetService.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    fixture.detectChanges();
    expect(component.loadState).toBe('error');

    assetService.get.and.returnValue(of(makeAsset({ id: 7, name: 'API de pagamentos' })));
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.sh-state button')?.click();
    fixture.detectChanges();

    expect(component.loadState).toBeNull();
    expect(component.form.value.name).toBe('API de pagamentos');
  });
});
