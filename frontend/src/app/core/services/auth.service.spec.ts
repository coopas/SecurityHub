import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { User } from '../models';
import { makeAuthResponse, makeJwt, makeUser } from '../testing/auth-test-utils';
import {
  ACCESS_TOKEN_STORAGE_KEY,
  AuthService,
  CURRENT_USER_STORAGE_KEY,
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
    service.logout();

    expect(last).toBeNull();
    expect(localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)).toBeNull();
    expect(localStorage.getItem(CURRENT_USER_STORAGE_KEY)).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
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
