import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { User } from '../models';
import { makeAuthResponse, makeJwt, makeRefreshToken, makeUser } from '../testing/auth-test-utils';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  AuthService,
  CURRENT_USER_STORAGE_KEY,
  REFRESH_TOKEN_STORAGE_KEY,
} from './auth.service';

describe('AuthService', () => {
  let httpMock: HttpTestingController;

  const configure = (): AuthService => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    const service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    return service;
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    if (httpMock) {
      httpMock.verify();
    }
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('guarda o token e emite o usuário após o login', (done) => {
    const service = configure();
    const response = makeAuthResponse('ANALYST');
    const emitted: (User | null)[] = [];
    service.currentUser$.subscribe((user) => emitted.push(user));

    service.login({ email: 'ana@empresa.com', password: 'senha-super-secreta' }).subscribe(() => {
      expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe(response.accessToken);
      expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe(response.refreshToken);
      expect(service.currentUser?.email).toBe('ana@empresa.com');
      expect(emitted.length).toBe(2);
      expect(emitted[0]).toBeNull();
      expect(emitted[1]?.role).toBe('ANALYST');
      expect(service.isAuthenticated()).toBeTrue();
      done();
    });

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/login`);
    expect(request.request.method).toBe('POST');
    request.flush(response);
  });

  it('envia o cadastro de empresa e inicia a sessão', (done) => {
    const service = configure();
    const response = makeAuthResponse('ADMIN');

    service
      .register({
        companyName: 'Empresa Teste',
        name: 'Ana Souza',
        email: 'ana@empresa.com',
        password: 'senha-super-secreta',
      })
      .subscribe(() => {
        expect(service.currentUser?.companyName).toBe('Empresa Teste');
        done();
      });

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/register`);
    expect(request.request.body.companyName).toBe('Empresa Teste');
    request.flush(response);
  });

  it('limpa o armazenamento e emite null no logout', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();
    expect(service.isAuthenticated()).toBeTrue();

    let last: User | null = makeUser();
    service.currentUser$.subscribe((user) => (last = user));
    service.logout().subscribe();

    expect(last).toBeNull();
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(CURRENT_USER_STORAGE_KEY)).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('revoga o refresh token no servidor ao sair', (done) => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();

    service.logout().subscribe(() => done());

    // A limpeza local não espera a rede: o token já saiu do armazenamento.
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/logout`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ refreshToken: makeRefreshToken() });
    request.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('conclui o logout mesmo quando a revogação falha', (done) => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();

    service.logout().subscribe({
      next: () => {
        expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
        expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
        expect(localStorage.getItem(CURRENT_USER_STORAGE_KEY)).toBeNull();
        expect(service.currentUser).toBeNull();
        done();
      },
      error: () => fail('o logout não pode propagar a falha da revogação'),
    });

    httpMock
      .expectOne(`${environment.apiUrl}/auth/logout`)
      .flush(null, { status: 500, statusText: 'Internal Server Error' });
  });

  it('sai sem chamar o servidor quando não há refresh token', (done) => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();

    service.logout().subscribe(() => {
      httpMock.expectNone(`${environment.apiUrl}/auth/logout`);
      expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
      done();
    });
  });

  it('clearSession limpa as três chaves sem tocar na rede', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();

    service.clearSession();

    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(CURRENT_USER_STORAGE_KEY)).toBeNull();
    expect(service.currentUser).toBeNull();
    httpMock.expectNone(`${environment.apiUrl}/auth/logout`);
  });

  it('troca o par de tokens em /auth/refresh', (done) => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(-60));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();
    const renewed = makeAuthResponse('ADMIN', 3600, makeRefreshToken('2'));

    service.refresh().subscribe((response) => {
      expect(response.accessToken).toBe(renewed.accessToken);
      expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBe(renewed.accessToken);
      expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe(makeRefreshToken('2'));
      done();
    });

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/refresh`);
    expect(request.request.body).toEqual({ refreshToken: makeRefreshToken() });
    request.flush(renewed);
  });

  it('falha sem chamar o servidor quando não há refresh token para renovar', (done) => {
    const service = configure();

    service.refresh().subscribe({
      next: () => fail('não deveria renovar sem token'),
      error: (error: unknown) => {
        httpMock.expectNone(`${environment.apiUrl}/auth/refresh`);
        expect(error instanceof Error).toBeTrue();
        done();
      },
    });
  });

  it('aceita access token vencido enquanto houver refresh token', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(-60));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser('ANALYST')));

    const service = configure();

    // A sessão é recuperável: mandá-la ao login seria descartar o que só precisava
    // de uma renovação.
    expect(service.currentUser?.role).toBe('ANALYST');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('para de aceitar a sessão depois que a renovação a limpa', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(-60));
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, makeRefreshToken());
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));
    const service = configure();
    expect(service.isAuthenticated()).toBeTrue();

    // É isto que faz o desvio para /login terminar: some também o refresh token.
    service.clearSession();

    expect(service.isAuthenticated()).toBeFalse();
  });

  it('pede a redefinição de senha e confirma com o token do e-mail', (done) => {
    const service = configure();

    service.requestPasswordReset({ email: 'ana@empresa.com' }).subscribe(() => {
      service
        .confirmPasswordReset({ token: 'token-do-email', password: 'senha-super-secreta' })
        .subscribe(() => {
          // Confirmar não abre sessão: o usuário volta ao login.
          expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
          done();
        });

      httpMock
        .expectOne(`${environment.apiUrl}/auth/password-reset/confirm`)
        .flush(null, { status: 204, statusText: 'No Content' });
    });

    const request = httpMock.expectOne(`${environment.apiUrl}/auth/password-reset/request`);
    expect(request.request.method).toBe('POST');
    request.flush(null, { status: 202, statusText: 'Accepted' });
  });

  it('restaura a sessão válida do armazenamento', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser('VIEWER')));

    const service = configure();

    expect(service.currentUser?.role).toBe('VIEWER');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('descarta token já expirado ao iniciar', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(-60));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));

    const service = configure();

    expect(service.currentUser).toBeNull();
    expect(service.accessToken).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('descarta token malformado ao iniciar', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'nao-e-um-jwt');
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));

    const service = configure();

    expect(service.currentUser).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('hasRole compara o papel do usuário autenticado', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser('DEVELOPER')));

    const service = configure();

    expect(service.hasRole('DEVELOPER')).toBeTrue();
    expect(service.hasRole('ADMIN', 'DEVELOPER')).toBeTrue();
    expect(service.hasRole('ADMIN')).toBeFalse();
    expect(service.hasRole()).toBeTrue();

    service.logout();
    expect(service.hasRole('DEVELOPER')).toBeFalse();
  });

  it('atualiza o usuário corrente a partir de /auth/me', (done) => {
    const service = configure();

    service.me().subscribe((user) => {
      expect(user.name).toBe('Ana Souza');
      expect(service.currentUser?.name).toBe('Ana Souza');
      done();
    });

    httpMock.expectOne(`${environment.apiUrl}/auth/me`).flush(makeUser());
  });
});
