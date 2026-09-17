import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  CURRENT_USER_STORAGE_KEY,
  REFRESH_TOKEN_STORAGE_KEY,
} from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { makeAuthResponse } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { makeInvitationPreview } from '../../users/testing/user-test-utils';
import { AcceptInvitationComponent } from './accept-invitation.component';

describe('AcceptInvitationComponent', () => {
  let fixture: ComponentFixture<AcceptInvitationComponent>;
  let component: AcceptInvitationComponent;
  let httpMock: HttpTestingController;
  let router: Router;
  let notifications: jasmine.SpyObj<NotificationService>;

  const previewUrl = `${environment.apiUrl}/invitations/accept?token=token-do-convite`;

  const setup = (queryParams: Record<string, string> = { token: 'token-do-convite' }): void => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [AcceptInvitationComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(AcceptInvitationComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('busca a prévia, anuncia empresa e papel e apaga o token da URL', () => {
    setup();

    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: {}, replaceUrl: true }),
    );

    const request = httpMock.expectOne(previewUrl);
    expect(request.request.method).toBe('GET');
    request.flush(makeInvitationPreview());
    fixture.detectChanges();

    expect(component.invitationSummary).toBe('Você foi convidado para Empresa Teste como Analista');
    const summary = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="invitation-summary"]',
    );
    expect(summary?.textContent).toContain('Você foi convidado para Empresa Teste como Analista');
  });

  it('mostra a recusa do backend quando o convite não vale mais', () => {
    setup();

    httpMock.expectOne(previewUrl).flush(
      {
        timestamp: '2026-09-17T12:00:00Z',
        status: 400,
        code: 'BAD_REQUEST',
        message: 'Convite inválido ou expirado',
        path: '/api/v1/invitations/accept',
        traceId: 'trace-1',
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(component.preview).toBeNull();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="invitation-error"]')
        ?.textContent,
    ).toContain('Convite inválido ou expirado');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="invitation-submit"]'),
    ).toBeNull();
  });

  it('recusa link sem token sem chamar o backend', () => {
    setup({});

    expect(component.previewError).not.toBeNull();
    httpMock.expectNone(previewUrl);
  });

  it('exige senha de 10 caracteres e confirmação igual', () => {
    setup();
    httpMock.expectOne(previewUrl).flush(makeInvitationPreview());

    component.form.setValue({ password: 'curta', confirmation: 'curta' });
    expect(component.form.controls['password'].hasError('minlength')).toBeTrue();

    component.form.setValue({ password: 'senha-super-secreta', confirmation: 'senha-diferente' });
    expect(component.form.controls['confirmation'].hasError('passwordMismatch')).toBeTrue();

    component.submit();
    httpMock.expectNone(`${environment.apiUrl}/invitations/accept`);
  });

  it('aceita o convite, guarda a sessão e vai ao dashboard', () => {
    setup();
    httpMock.expectOne(previewUrl).flush(makeInvitationPreview());

    component.form.setValue({ password: 'senha-super-secreta', confirmation: 'senha-super-secreta' });
    component.submit();

    const response = makeAuthResponse('ANALYST');
    const request = httpMock.expectOne(`${environment.apiUrl}/invitations/accept`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      token: 'token-do-convite',
      password: 'senha-super-secreta',
    });
    request.flush(response);

    // O aceite já devolve o par de tokens: não há por que passar pelo login.
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe(response.accessToken);
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe(response.refreshToken);
    expect(localStorage.getItem(CURRENT_USER_STORAGE_KEY)).not.toBeNull();
    expect(notifications.success).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });
});
