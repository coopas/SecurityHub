import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { ApiError } from '../../../core/models';
import { makeAuthResponse } from '../../../core/testing/auth-test-utils';
import { SharedModule } from '../../../shared/shared.module';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let httpMock: HttpTestingController;
  let router: Router;

  const configure = (queryParams: Record<string, string> = {}): void => {
    TestBed.configureTestingModule({
      declarations: [LoginComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
      providers: [
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap(queryParams) } },
        },
      ],
    });

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('não envia formulário inválido', () => {
    configure();

    component.submit();

    expect(component.form.invalid).toBeTrue();
    expect(component.form.controls['email'].touched).toBeTrue();
    httpMock.expectNone(`${environment.apiUrl}/auth/login`);
  });

  it('autentica e navega para o returnUrl', () => {
    configure({ returnUrl: '/vulnerabilities' });
    component.form.setValue({ email: 'ana@empresa.com', password: 'senha-super-secreta' });

    component.submit();
    expect(component.submitting).toBeTrue();

    httpMock.expectOne(`${environment.apiUrl}/auth/login`).flush(makeAuthResponse());

    expect(component.submitting).toBeFalse();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/vulnerabilities');
  });

  it('ignora returnUrl externo e usa o dashboard', () => {
    configure({ returnUrl: '//evil.example.com' });
    component.form.setValue({ email: 'ana@empresa.com', password: 'senha-super-secreta' });

    component.submit();
    httpMock.expectOne(`${environment.apiUrl}/auth/login`).flush(makeAuthResponse());

    expect(router.navigateByUrl).toHaveBeenCalledWith('/dashboard');
  });

  it('aplica fieldErrors do backend nos controles do formulário', () => {
    configure();
    component.form.setValue({ email: 'ana@empresa.com', password: 'senha-super-secreta' });

    component.submit();

    const body: ApiError = {
      timestamp: '2026-09-17T12:00:00Z',
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'Dados inválidos',
      path: '/api/v1/auth/login',
      fieldErrors: [{ field: 'email', message: 'deve ser um e-mail válido' }],
      traceId: 'trace-1',
    };
    httpMock
      .expectOne(`${environment.apiUrl}/auth/login`)
      .flush(body, { status: 400, statusText: 'Bad Request' });

    expect(component.form.controls['email'].getError('server')).toBe('deve ser um e-mail válido');
    expect(component.submitting).toBeFalse();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('exibe mensagem geral quando o backend recusa as credenciais', () => {
    configure();
    component.form.setValue({ email: 'ana@empresa.com', password: 'senha-super-secreta' });

    component.submit();

    httpMock.expectOne(`${environment.apiUrl}/auth/login`).flush(
      {
        timestamp: '2026-09-17T12:00:00Z',
        status: 401,
        code: 'UNAUTHORIZED',
        message: 'Credenciais inválidas',
        path: '/api/v1/auth/login',
        traceId: 'trace-2',
      },
      { status: 401, statusText: 'Unauthorized' },
    );

    fixture.detectChanges();
    expect(component.generalError).toBe('Credenciais inválidas');
    const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Credenciais inválidas');
  });
});
