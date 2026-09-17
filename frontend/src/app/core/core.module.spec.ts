import { HttpClient } from '@angular/common/http';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';

import { environment } from '../../environments/environment';
import { CoreModule } from './core.module';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from './services/auth.service';
import { makeJwt, makeUser } from './testing/auth-test-utils';

describe('CoreModule', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('impede a importação duplicada', () => {
    expect(() => new CoreModule({} as CoreModule)).toThrowError(/já foi carregado/);
  });

  it('permite a importação única', () => {
    expect(() => new CoreModule()).not.toThrow();
  });

  /**
   * Os interceptors dependem de serviços que usam o próprio HttpClient; este teste
   * garante que a resolução preguiçosa evita a dependência cíclica em tempo de execução.
   */
  it('registra os interceptors sem dependência cíclica', () => {
    const token = makeJwt(3600);
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
    localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser()));

    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, RouterTestingModule, CoreModule],
    });

    const http = TestBed.inject(HttpClient);
    const httpMock = TestBed.inject(HttpTestingController);

    http.get(`${environment.apiUrl}/projects`).subscribe();

    const request = httpMock.expectOne(`${environment.apiUrl}/projects`);
    expect(request.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
    request.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, sort: '' });
    httpMock.verify();
  });
});
