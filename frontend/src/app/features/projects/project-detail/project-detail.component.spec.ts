import { HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { of, throwError } from 'rxjs';

import { ApiError, Role } from '../../../core/models';
import { NotificationService } from '../../../core/services/notification.service';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../../core/services/auth.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { makeProject } from '../testing/project-test-utils';
import { ProjectService } from '../services/project.service';
import { ProjectDetailComponent } from './project-detail.component';

describe('ProjectDetailComponent', () => {
  let fixture: ComponentFixture<ProjectDetailComponent>;
  let component: ProjectDetailComponent;
  let projectService: jasmine.SpyObj<ProjectService>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

  const setup = (role: Role = 'ADMIN', id = '7'): void => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));

    projectService = jasmine.createSpyObj<ProjectService>('ProjectService', ['get', 'delete']);
    projectService.get.and.returnValue(
      of(makeProject({ id: 7, name: 'Portal do cliente', assetCount: 4, createdByName: 'Ana Souza' })),
    );
    projectService.delete.and.returnValue(of(undefined));
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      declarations: [ProjectDetailComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: ProjectService, useValue: projectService },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id }) } } },
      ],
    });

    fixture = TestBed.createComponent(ProjectDetailComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('exibe os dados do projeto', () => {
    setup();
    fixture.detectChanges();

    expect(projectService.get).toHaveBeenCalledWith(7);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Portal do cliente');
    expect(text).toContain('Ana Souza');
    expect(text).toContain('Ativo');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="project-asset-count"]')?.textContent,
    ).toContain('4');
  });

  it('esconde editar e excluir para VIEWER', () => {
    setup('VIEWER');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="project-edit"]')).toBeNull();
    expect(element.querySelector('[data-testid="project-delete"]')).toBeNull();
  });

  it('mostra editar e excluir para ADMIN', () => {
    setup('ADMIN');
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="project-edit"]')).not.toBeNull();
    expect(element.querySelector('[data-testid="project-delete"]')).not.toBeNull();
  });

  it('exclui após confirmação e volta para a lista', () => {
    setup();
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, boolean>);

    component.confirmDelete();

    expect(projectService.delete).toHaveBeenCalledWith(7);
    expect(notifications.success).toHaveBeenCalledWith('Projeto excluído.');
    expect(router.navigate).toHaveBeenCalledWith(['/projects']);
  });

  it('mostra a mensagem do servidor quando a exclusão conflita', () => {
    setup();
    fixture.detectChanges();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, boolean>);

    const conflict: ApiError = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 409,
      code: 'CONFLICT',
      message: 'Exclua os ativos do projeto antes',
      path: '/api/v1/projects/7',
      traceId: 'trace',
    };
    projectService.delete.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: conflict })),
    );

    component.confirmDelete();
    fixture.detectChanges();

    expect(component.actionError).toBe('Exclua os ativos do projeto antes');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Exclua os ativos do projeto antes');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('trata 404 como projeto não encontrado', () => {
    setup('ADMIN', '99');
    const notFound: ApiError = {
      timestamp: '2026-01-01T00:00:00Z',
      status: 404,
      code: 'NOT_FOUND',
      message: 'Projeto 99 não encontrado',
      path: '/api/v1/projects/99',
      traceId: 'trace',
    };
    projectService.get.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: notFound })),
    );

    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Projeto não encontrado.');
  });
});
