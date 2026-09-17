import { HTTP_INTERCEPTORS, HttpClient } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../services/auth.service';
import { makeJwt, makeUser } from '../testing/auth-test-utils';
import { AuthInterceptor } from './auth.interceptor';

describe('AuthInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let token: string;

  beforeEach(() => {
    localStorage.clear();
    token = makeJwt(3600);
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [{ provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true }],
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('anexa o bearer token às chamadas da API', () => {
    http.get(`${environment.apiUrl}/projects`).subscribe();

    const request = httpMock.expectOne(`${environment.apiUrl}/projects`);
    expect(request.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    request.flush({});
  });

  it('não anexa o token a URLs fora da API', () => {
    http.get('https://cdn.exemplo.com/logo.svg').subscribe();

    const request = httpMock.expectOne('https://cdn.exemplo.com/logo.svg');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });

  it('não envia cabeçalho quando não há sessão', () => {
    localStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [{ provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor, multi: true }],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);

    http.get(`${environment.apiUrl}/projects`).subscribe();

    const request = httpMock.expectOne(`${environment.apiUrl}/projects`);
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });
});
