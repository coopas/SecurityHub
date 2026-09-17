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
import { makeProject } from '../testing/project-test-utils';
import { ProjectService } from '../services/project.service';
import { ProjectFormComponent } from './project-form.component';

const apiError = (status: number, code: ApiError['code'], message: string, fieldErrors?: ApiError['fieldErrors']): HttpErrorResponse =>
  new HttpErrorResponse({
    status,
    error: {
      timestamp: '2026-01-01T00:00:00Z',
      status,
      code,
      message,
      path: '/api/v1/projects',
      fieldErrors,
      traceId: 'trace',
    } as ApiError,
  });

describe('ProjectFormComponent', () => {
  let fixture: ComponentFixture<ProjectFormComponent>;
  let component: ProjectFormComponent;
  let projectService: jasmine.SpyObj<ProjectService>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let router: Router;

  const setup = (id?: string): void => {
    projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['get', 'create', 'update']);
    projectService.get.and.returnValue(of(makeProject({ id: 7, name: 'Portal', description: 'Desc' })));
    projectService.create.and.returnValue(of(makeProject({ id: 12 })));
    projectService.update.and.returnValue(of(makeProject({ id: 7 })));
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);

    TestBed.configureTestingModule({
      declarations: [ProjectFormComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: ProjectService, useValue: projectService },
        { provide: NotificationService, useValue: notifications },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap(id ? { id } : {}) } },
        },
      ],
    });

    fixture = TestBed.createComponent(ProjectFormComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  afterEach(() => TestBed.resetTestingModule());

  it('não envia formulário inválido e marca os campos', () => {
    setup();
    fixture.detectChanges();

    component.submit();

    expect(projectService.create).not.toHaveBeenCalled();
    expect(component.form.controls['name'].touched).toBeTrue();
  });

  it('cria, avisa e navega para o detalhe', () => {
    setup();
    fixture.detectChanges();

    component.form.patchValue({ name: '  Novo projeto  ', description: '  ', status: 'ARCHIVED' });
    component.submit();

    expect(projectService.create).toHaveBeenCalledWith({
      name: 'Novo projeto',
      description: undefined,
      status: 'ARCHIVED',
    });
    expect(notifications.success).toHaveBeenCalledWith('Projeto criado.');
    expect(router.navigate).toHaveBeenCalledWith(['/projects', 12]);
    expect(component.submitting).toBeFalse();
  });

  it('carrega o projeto e atualiza no modo edição', () => {
    setup('7');
    fixture.detectChanges();

    expect(projectService.get).toHaveBeenCalledWith(7);
    expect(component.isEdit).toBeTrue();
    expect(component.form.value.name).toBe('Portal');

    component.form.patchValue({ name: 'Portal renomeado' });
    component.submit();

    expect(projectService.update).toHaveBeenCalledWith(7, {
      name: 'Portal renomeado',
      description: 'Desc',
      status: 'ACTIVE',
    });
    expect(notifications.success).toHaveBeenCalledWith('Projeto atualizado.');
  });

  it('mapeia fieldErrors de 400 nos controles', () => {
    setup();
    fixture.detectChanges();

    projectService.create.and.returnValue(
      throwError(() =>
        apiError(400, 'VALIDATION_ERROR', 'Dados inválidos', [
          { field: 'name', message: 'deve ter entre 2 e 140 caracteres' },
        ]),
      ),
    );

    component.form.patchValue({ name: 'Projeto' });
    component.submit();
    fixture.detectChanges();

    expect(component.form.controls['name'].getError('server')).toBe('deve ter entre 2 e 140 caracteres');
    expect(component.generalError).toBeNull();
    expect(component.submitting).toBeFalse();
  });

  it('exibe a mensagem do servidor em 409 de nome duplicado', () => {
    setup();
    fixture.detectChanges();

    projectService.create.and.returnValue(
      throwError(() => apiError(409, 'CONFLICT', 'Já existe um projeto com esse nome nesta empresa')),
    );

    component.form.patchValue({ name: 'Portal' });
    component.submit();
    fixture.detectChanges();

    expect(component.generalError).toBe('Já existe um projeto com esse nome nesta empresa');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Já existe um projeto com esse nome nesta empresa',
    );
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('trata 404 na edição como projeto não encontrado, sem retry', () => {
    setup('99');
    projectService.get.and.returnValue(throwError(() => apiError(404, 'NOT_FOUND', 'Projeto 99 não encontrado')));

    fixture.detectChanges();

    expect(component.loadState).toBe('error');
    expect(component.notFound).toBeTrue();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Projeto não encontrado.');
    expect(element.querySelector('.sh-state button')).toBeNull();
  });
});
