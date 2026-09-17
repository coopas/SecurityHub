import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { NotificationService } from '../../../core/services/notification.service';
import { SharedModule } from '../../../shared/shared.module';
import { makeInvitation } from '../testing/user-test-utils';
import { InviteFormComponent } from './invite-form.component';

describe('InviteFormComponent', () => {
  let fixture: ComponentFixture<InviteFormComponent>;
  let component: InviteFormComponent;
  let httpMock: HttpTestingController;
  let router: Router;
  let notifications: jasmine.SpyObj<NotificationService>;

  const invitationsUrl = `${environment.apiUrl}/invitations`;

  const setup = (): void => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [InviteFormComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [{ provide: NotificationService, useValue: notifications }],
    });

    fixture = TestBed.createComponent(InviteFormComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
  };

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('não envia formulário inválido', () => {
    setup();

    component.submit();

    expect(component.form.invalid).toBeTrue();
    httpMock.expectNone(invitationsUrl);
  });

  it('envia o convite e volta à listagem', () => {
    setup();
    component.form.setValue({ name: ' Bruno Lima ', email: 'bruno@empresa.com', role: 'ANALYST' });

    component.submit();

    const request = httpMock.expectOne(invitationsUrl);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'Bruno Lima',
      email: 'bruno@empresa.com',
      role: 'ANALYST',
    });
    request.flush(makeInvitation());

    expect(notifications.success).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/users');
  });

  it('mostra o conflito de e-mail já convidado', () => {
    setup();
    component.form.setValue({ name: 'Bruno Lima', email: 'bruno@empresa.com', role: 'VIEWER' });

    component.submit();
    httpMock.expectOne(invitationsUrl).flush(
      {
        timestamp: '2026-09-17T12:00:00Z',
        status: 409,
        code: 'CONFLICT',
        message: 'E-mail já cadastrado ou convidado',
        path: '/api/v1/invitations',
        traceId: 'trace-1',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();

    expect(component.generalError).toBe('E-mail já cadastrado ou convidado');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('aplica fieldErrors do backend nos controles', () => {
    setup();
    component.form.setValue({ name: 'Bruno Lima', email: 'bruno@empresa.com', role: 'VIEWER' });

    component.submit();
    httpMock.expectOne(invitationsUrl).flush(
      {
        timestamp: '2026-09-17T12:00:00Z',
        status: 400,
        code: 'VALIDATION_ERROR',
        message: 'Dados inválidos',
        path: '/api/v1/invitations',
        fieldErrors: [{ field: 'email', message: 'deve ser um e-mail válido' }],
        traceId: 'trace-2',
      },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(component.form.controls['email'].getError('server')).toBe('deve ser um e-mail válido');
  });
});
