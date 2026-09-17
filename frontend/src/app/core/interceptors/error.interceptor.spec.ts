import { HTTP_INTERCEPTORS, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { environment } from '../../../environments/environment';
import { ApiError } from '../models';
import { ACCESS_TOKEN_STORAGE_KEY, AuthService, CURRENT_USER_STORAGE_KEY } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';
import { makeJwt, makeUser } from '../testing/auth-test-utils';
import { ErrorInterceptor } from './error.interceptor';

describe('ErrorInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;
  let notifications: jasmine.SpyObj<NotificationService>;
  let authService: AuthService;

  const apiError = (status: number, code: ApiError['code'], message: string): ApiError => ({
    timestamp: '2026-09-17T12:00:00Z',
    status,
    code,
    message,
    path: '/api/v1/vulnerabilities',
    traceId: 'trace-1',
  });

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));

    router = jasmine.createSpyObj<Router>('Router', ['navigate'], { url: '/vulnerabilities' });
    router.navigate.and.resolveTo(true);
    notifications = jasmine.createSpyObj<NotificationService>('NotificationService', ['success', 'error']);

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [
        { provide: Router, useValue: router },
        { provide: NotificationService, useValue: notifications },
        { provide: HTTP_INTERCEPTORS, useClass: ErrorInterceptor, multi: true },
      ],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('em 401 encerra a sessão e volta ao login preservando o destino', (done) => {
    spyOn(authService, 'logout').and.callThrough();

    http.get(`${environment.apiUrl}/vulnerabilities`).subscribe({
      error: (error: unknown) => {
        expect(error instanceof HttpErrorResponse).toBeTrue();
        expect(authService.logout).toHaveBeenCalled();
        expect(router.navigate).toHaveBeenCalledWith(['/login'], {
          queryParams: { returnUrl: '/vulnerabilities' },
        });
        expect(notifications.error).toHaveBeenCalled();
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/vulnerabilities`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Não autenticado'), { status: 401, statusText: 'Unauthorized' });
  });

  it('em 403 redireciona para a página de acesso negado', (done) => {
    http.get(`${environment.apiUrl}/users`).subscribe({
      error: () => {
        expect(router.navigate).toHaveBeenCalledWith(['/403']);
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/users`)
      .flush(apiError(403, 'FORBIDDEN', 'Acesso negado'), { status: 403, statusText: 'Forbidden' });
  });

  it('em falha de rede avisa que o servidor está indisponível', (done) => {
    http.get(`${environment.apiUrl}/projects`).subscribe({
      error: () => {
        expect(notifications.error).toHaveBeenCalledWith(
          'Servidor indisponível. Verifique sua conexão e tente novamente.',
        );
        expect(router.navigate).not.toHaveBeenCalled();
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/projects`)
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
  });

  it('não exibe snackbar para erro de validação', (done) => {
    http.post(`${environment.apiUrl}/projects`, {}).subscribe({
      error: () => {
        expect(notifications.error).not.toHaveBeenCalled();
        done();
      },
    });

    httpMock.expectOne(`${environment.apiUrl}/projects`).flush(
      { ...apiError(400, 'VALIDATION_ERROR', 'Dados inválidos'), fieldErrors: [{ field: 'name', message: 'é obrigatório' }] },
      { status: 400, statusText: 'Bad Request' },
    );
  });

  it('em 401 no login apenas notifica, sem derrubar a navegação', (done) => {
    spyOn(authService, 'logout');

    http.post(`${environment.apiUrl}/auth/login`, {}).subscribe({
      error: () => {
        expect(authService.logout).not.toHaveBeenCalled();
        expect(router.navigate).not.toHaveBeenCalled();
        expect(notifications.error).toHaveBeenCalledWith('Credenciais inválidas');
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/auth/login`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Credenciais inválidas'), { status: 401, statusText: 'Unauthorized' });
  });

  it('relança o erro para que a tela possa reagir', (done) => {
    http.get(`${environment.apiUrl}/projects/1`).subscribe({
      error: (error: unknown) => {
        expect((error as HttpErrorResponse).status).toBe(404);
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/projects/1`)
      .flush(apiError(404, 'NOT_FOUND', 'Projeto não encontrado'), { status: 404, statusText: 'Not Found' });
  });
});
