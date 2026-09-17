import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../../../environments/environment';
import { SharedModule } from '../../../shared/shared.module';
import {
  ForgotPasswordComponent,
  PASSWORD_RESET_NEUTRAL_MESSAGE,
} from './forgot-password.component';

describe('ForgotPasswordComponent', () => {
  let fixture: ComponentFixture<ForgotPasswordComponent>;
  let component: ForgotPasswordComponent;
  let httpMock: HttpTestingController;

  const setup = (): void => {
    TestBed.configureTestingModule({
      declarations: [ForgotPasswordComponent],
      imports: [SharedModule, HttpClientTestingModule, RouterTestingModule, NoopAnimationsModule],
    });

    fixture = TestBed.createComponent(ForgotPasswordComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  };

  const submitWith = (email: string): void => {
    component.form.setValue({ email });
    component.submit();
    httpMock
      .expectOne(`${environment.apiUrl}/auth/password-reset/request`)
      .flush(null, { status: 202, statusText: 'Accepted' });
    fixture.detectChanges();
  };

  const renderedPanel = (): string =>
    (
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="forgot-neutral"]')
        ?.textContent ?? ''
    ).trim();

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('não envia formulário inválido', () => {
    setup();

    component.submit();

    expect(component.form.invalid).toBeTrue();
    expect(component.form.controls['email'].touched).toBeTrue();
    httpMock.expectNone(`${environment.apiUrl}/auth/password-reset/request`);
  });

  it('envia o e-mail informado e troca o formulário pelo painel neutro', () => {
    setup();

    submitWith('ana@empresa.com');

    expect(component.submitted).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="forgot-email"]'))
      .toBeNull();
    expect(renderedPanel()).toBe(PASSWORD_RESET_NEUTRAL_MESSAGE);
  });

  it('mostra exatamente a mesma mensagem para conta existente e inexistente', () => {
    setup();
    submitWith('ana@empresa.com');
    const conhecido = renderedPanel();

    TestBed.resetTestingModule();
    setup();
    submitWith('ninguem@exemplo.com');
    const desconhecido = renderedPanel();

    // The backend answers 202 in both cases; any difference here would turn into an account
    // enumerator.
    expect(conhecido).toBe(desconhecido);
    expect(conhecido).toBe(PASSWORD_RESET_NEUTRAL_MESSAGE);
  });

  it('mantém o formulário e mostra erro quando o envio falha no servidor', () => {
    setup();
    component.form.setValue({ email: 'ana@empresa.com' });

    component.submit();
    httpMock
      .expectOne(`${environment.apiUrl}/auth/password-reset/request`)
      .flush(
        {
          timestamp: '2026-09-17T12:00:00Z',
          status: 500,
          code: 'INTERNAL_ERROR',
          message: 'Falha ao enviar o e-mail',
          path: '/api/v1/auth/password-reset/request',
          traceId: 'trace-1',
        },
        { status: 500, statusText: 'Internal Server Error' },
      );
    fixture.detectChanges();

    // Hiding the failure would leave the user waiting for an e-mail that was never sent.
    expect(component.submitted).toBeFalse();
    expect(component.generalError).toBe('Falha ao enviar o e-mail');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent,
    ).toContain('Falha ao enviar o e-mail');
  });
});
