import { HTTP_INTERCEPTORS, HttpClient, HttpErrorResponse } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { environment } from '../../../environments/environment';
import { ApiError } from '../models';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  AuthService,
  CURRENT_USER_STORAGE_KEY,
  REFRESH_TOKEN_STORAGE_KEY,
} from '../services/auth.service';
import { NotificationService } from '../services/notification.service';
import { makeAuthResponse, makeJwt, makeRefreshToken, makeUser } from '../testing/auth-test-utils';
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

  /** Sessão renovável: é o que distingue o 401 recuperável do 401 terminal. */
  const seedRefreshToken = (): void => {
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
  };

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

  it('em 401 sem refresh token encerra a sessão e volta ao login preservando o destino', (done) => {
    spyOn(authService, 'clearSession').and.callThrough();

    http.get(`${environment.apiUrl}/vulnerabilities`).subscribe({
      error: (error: unknown) => {
        expect(error instanceof HttpErrorResponse).toBeTrue();
        expect(authService.clearSession).toHaveBeenCalled();
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

  it('não notifica genericamente quando a resposta é um blob', (done) => {
    http
      .get(`${environment.apiUrl}/vulnerabilities/export`, { responseType: 'blob' })
      .subscribe({
        error: () => {
          // O corpo de erro também é um Blob, então a mensagem real só existe depois que o
          // chamador o lê. Notificar aqui daria dois avisos para o mesmo evento.
          expect(notifications.error).not.toHaveBeenCalled();
          done();
        },
      });

    httpMock
      .expectOne(`${environment.apiUrl}/vulnerabilities/export`)
      .flush(new Blob(['{"message":"refine os filtros"}']), {
        status: 400,
        statusText: 'Bad Request',
      });
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
    seedRefreshToken();
    spyOn(authService, 'clearSession');

    http.post(`${environment.apiUrl}/auth/login`, {}).subscribe({
      error: () => {
        expect(authService.clearSession).not.toHaveBeenCalled();
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

  it('em 401 renova a sessão e repete a requisição com o cabeçalho novo', (done) => {
    seedRefreshToken();
    const renewed = makeAuthResponse('ADMIN', 3600, makeRefreshToken('2'));

    http.get<{ id: number }>(`${environment.apiUrl}/vulnerabilities`).subscribe({
      next: (body) => {
        expect(body.id).toBe(7);
        expect(router.navigate).not.toHaveBeenCalled();
        expect(notifications.error).not.toHaveBeenCalled();
        expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe(makeRefreshToken('2'));
        done();
      },
      error: () => fail('a requisição repetida deveria ter sucesso'),
    });

    httpMock
      .expectOne(`${environment.apiUrl}/vulnerabilities`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });

    const refresh = httpMock.expectOne(`${environment.apiUrl}/auth/refresh`);
    expect(refresh.request.method).toBe('POST');
    expect(refresh.request.body).toEqual({ refreshToken: makeRefreshToken() });
    refresh.flush(renewed);

    const retry = httpMock.expectOne(`${environment.apiUrl}/vulnerabilities`);
    expect(retry.request.headers.get('Authorization')).toBe(`Bearer ${renewed.accessToken}`);
    retry.flush({ id: 7 });
  });

  it('duas 401 simultâneas compartilham uma única renovação e ambas são repetidas', (done) => {
    seedRefreshToken();
    const renewed = makeAuthResponse('ADMIN', 3600, makeRefreshToken('2'));
    const bodies: string[] = [];

    const collect = (value: { name: string }): void => {
      bodies.push(value.name);
      if (bodies.length === 2) {
        expect(bodies.sort()).toEqual(['ativos', 'projetos']);
        done();
      }
    };

    http.get<{ name: string }>(`${environment.apiUrl}/projects`).subscribe({ next: collect });
    http.get<{ name: string }>(`${environment.apiUrl}/assets`).subscribe({ next: collect });

    httpMock
      .expectOne(`${environment.apiUrl}/projects`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });
    httpMock
      .expectOne(`${environment.apiUrl}/assets`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });

    const refreshes = httpMock.match(`${environment.apiUrl}/auth/refresh`);
    expect(refreshes.length).toBe(1);
    refreshes[0].flush(renewed);

    const projects = httpMock.expectOne(`${environment.apiUrl}/projects`);
    const assets = httpMock.expectOne(`${environment.apiUrl}/assets`);
    expect(projects.request.headers.get('Authorization')).toBe(`Bearer ${renewed.accessToken}`);
    expect(assets.request.headers.get('Authorization')).toBe(`Bearer ${renewed.accessToken}`);
    projects.flush({ name: 'projetos' });
    assets.flush({ name: 'ativos' });
  });

  it('renovação recusada encerra a sessão sem repetir nem notificar duas vezes', (done) => {
    seedRefreshToken();
    spyOn(authService, 'clearSession').and.callThrough();

    http.get(`${environment.apiUrl}/vulnerabilities`).subscribe({
      error: (error: unknown) => {
        expect((error as HttpErrorResponse).status).toBe(401);
        expect(authService.clearSession).toHaveBeenCalledTimes(1);
        expect(notifications.error).toHaveBeenCalledTimes(1);
        expect(notifications.error).toHaveBeenCalledWith(
          'Sua sessão expirou. Entre novamente para continuar.',
        );
        expect(router.navigate).toHaveBeenCalledWith(['/login'], {
          queryParams: { returnUrl: '/vulnerabilities' },
        });
        expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/vulnerabilities`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });

    // O 401 da própria renovação não pode disparar outra renovação: `httpMock.verify()`
    // no afterEach reprova qualquer requisição pendente além desta.
    httpMock
      .expectOne(`${environment.apiUrl}/auth/refresh`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Refresh token inválido'), {
        status: 401,
        statusText: 'Unauthorized',
      });
  });

  it('uma nova rajada de 401 depois da renovação anterior pede outra renovação', (done) => {
    seedRefreshToken();

    http.get(`${environment.apiUrl}/projects`).subscribe({ next: () => primeiraConcluida() });

    httpMock
      .expectOne(`${environment.apiUrl}/projects`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });
    httpMock
      .expectOne(`${environment.apiUrl}/auth/refresh`)
      .flush(makeAuthResponse('ADMIN', 3600, makeRefreshToken('2')));
    httpMock.expectOne(`${environment.apiUrl}/projects`).flush({});

    function primeiraConcluida(): void {
      http.get(`${environment.apiUrl}/assets`).subscribe({ next: () => done() });

      httpMock
        .expectOne(`${environment.apiUrl}/assets`)
        .flush(apiError(401, 'UNAUTHORIZED', 'Token expirado'), { status: 401, statusText: 'Unauthorized' });

      const refresh = httpMock.expectOne(`${environment.apiUrl}/auth/refresh`);
      expect(refresh.request.body).toEqual({ refreshToken: makeRefreshToken('2') });
      refresh.flush(makeAuthResponse('ADMIN', 3600, makeRefreshToken('3')));

      httpMock.expectOne(`${environment.apiUrl}/assets`).flush({});
    }
  });

  it('em 401 na prévia do convite apenas notifica, sem tentar renovar', (done) => {
    seedRefreshToken();
    spyOn(authService, 'clearSession');

    http.get(`${environment.apiUrl}/invitations/accept?token=abc`).subscribe({
      error: () => {
        expect(authService.clearSession).not.toHaveBeenCalled();
        expect(notifications.error).toHaveBeenCalledWith('Convite inválido');
        done();
      },
    });

    httpMock
      .expectOne(`${environment.apiUrl}/invitations/accept?token=abc`)
      .flush(apiError(401, 'UNAUTHORIZED', 'Convite inválido'), { status: 401, statusText: 'Unauthorized' });
  });
});
