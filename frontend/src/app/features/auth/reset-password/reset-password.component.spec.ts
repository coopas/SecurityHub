import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { NotificationService } from '../../../core/services/notification.service';
import { SharedModule } from '../../../shared/shared.module';
import { ResetPasswordComponent } from './reset-password.component';

describe('ResetPasswordComponent', () => {
  let fixture: ComponentFixture<ResetPasswordComponent>;
  let component: ResetPasswordComponent;
  let httpMock: HttpTestingController;
  let router: Router;
  let notifications: jasmine.SpyObj<NotificationService>;

  const activatedRoute = (queryParams: Record<string, string>): unknown => ({
    snapshot: { queryParamMap: convertToParamMap(queryParams) },
  });

  const setup = (queryParams: Record<string, string> = { token: 'token-do-email' }): void => {
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', [
      'success',
      'error',
    ]);

    TestBed.configureTestingModule({
      declarations: [ResetPasswordComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        { provide: ActivatedRoute, useValue: activatedRoute(queryParams) },
        { provide: NotificationService, useValue: notifications },
      ],
    });

    fixture = TestBed.createComponent(ResetPasswordComponent);
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

  it('guarda o token e o apaga da URL substituindo o histórico', () => {
    setup({ token: 'token-do-email' });

    expect(component.token).toBe('token-do-email');
    // Without this the token would stay in the history, in the bookmarks and in any Referer.
    expect(router.navigate).toHaveBeenCalledWith(
      [],
      jasmine.objectContaining({ queryParams: {}, replaceUrl: true }),
    );
  });

  it('recusa o link sem token e nem tenta navegar', () => {
    setup({});

    expect(component.hasToken).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="reset-missing-token"]'),
    ).not.toBeNull();
  });

  it('exige senha com pelo menos 10 caracteres', () => {
    setup();

    component.form.setValue({ confirmation: 'curta', password: 'curta' });

    expect(component.form.controls['password'].hasError('minlength')).toBeTrue();
    expect(component.form.invalid).toBeTrue();

    component.submit();
    httpMock.expectNone(`${environment.apiUrl}/auth/password-reset/confirm`);
  });

  it('exige que a confirmação repita a senha', () => {
    setup();

    component.form.setValue({ password: 'senha-super-secreta', confirmation: 'outra-coisa-aqui' });

    expect(component.form.controls['confirmation'].hasError('passwordMismatch')).toBeTrue();
    expect(component.form.invalid).toBeTrue();

    component.form.patchValue({ confirmation: 'senha-super-secreta' });

    expect(component.form.controls['confirmation'].hasError('passwordMismatch')).toBeFalse();
    expect(component.form.valid).toBeTrue();
  });

  it('confirma a redefinição e manda o usuário ao login', () => {
    setup();
    component.form.setValue({ password: 'senha-super-secreta', confirmation: 'senha-super-secreta' });

    component.submit();

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/password-reset/confirm`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      token: 'token-do-email',
      password: 'senha-super-secreta',
    });
    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(notifications.success).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('mostra a recusa do backend para token expirado', () => {
    setup();
    component.form.setValue({ password: 'senha-super-secreta', confirmation: 'senha-super-secreta' });

    component.submit();
    httpMock.expectOne(`${environment.apiUrl}/auth/password-reset/confirm`).flush(
      {
        timestamp: '2026-09-17T12:00:00Z',
        status: 400,
        code: 'BAD_REQUEST',
        message: 'Link de redefinição inválido ou expirado',
        path: '/api/v1/auth/password-reset/confirm',
        traceId: 'trace-1',
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    expect(component.generalError).toBe('Link de redefinição inválido ou expirado');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
