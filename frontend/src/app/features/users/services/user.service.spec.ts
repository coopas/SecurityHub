import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { makeAdminUser, makeCompanyUser } from '../testing/user-test-utils';
import { UserService } from './user.service';

describe('UserService', () => {
  let service: UserService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.apiUrl}/users`;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(UserService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista sem parâmetros quando não há filtro', (done) => {
    service.list().subscribe((users) => {
      expect(users.length).toBe(2);
      done();
    });

    const request = httpMock.expectOne(baseUrl);
    expect(request.request.params.keys().length).toBe(0);
    request.flush([makeAdminUser(), makeCompanyUser(2)]);
  });

  it('envia apenas os filtros preenchidos', (done) => {
    service.list({ role: 'VIEWER', active: false, search: 'ana' }).subscribe(() => done());

    const request = httpMock.expectOne(
      (candidate) => candidate.url === baseUrl && candidate.params.get('role') === 'VIEWER',
    );
    expect(request.request.params.get('active')).toBe('false');
    expect(request.request.params.get('search')).toBe('ana');
    request.flush([]);
  });

  it('atualiza somente o nome', (done) => {
    service.update(2, { name: 'Bruno Lima' }).subscribe(() => done());

    const request = httpMock.expectOne(`${baseUrl}/2`);
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ name: 'Bruno Lima' });
    request.flush(makeCompanyUser(2, 'ANALYST', { name: 'Bruno Lima' }));
  });

  it('usa endpoints próprios para papel e situação', (done) => {
    service.changeRole(2, 'ADMIN').subscribe(() => {
      service.changeActive(2, false).subscribe(() => done());

      const active = httpMock.expectOne(`${baseUrl}/2/active`);
      expect(active.request.method).toBe('PATCH');
      expect(active.request.body).toEqual({ active: false });
      active.flush(makeCompanyUser(2, 'ADMIN', { active: false }));
    });

    const role = httpMock.expectOne(`${baseUrl}/2/role`);
    expect(role.request.method).toBe('PATCH');
    expect(role.request.body).toEqual({ role: 'ADMIN' });
    role.flush(makeCompanyUser(2, 'ADMIN'));
  });

  it('busca um usuário por identificador', (done) => {
    service.get(2).subscribe((user) => {
      expect(user.id).toBe(2);
      done();
    });

    httpMock.expectOne(`${baseUrl}/2`).flush(makeCompanyUser(2));
  });
});
