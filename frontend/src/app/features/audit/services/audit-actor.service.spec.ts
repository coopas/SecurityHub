import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { User } from '../../../core/models';
import { AuditActorOption } from '../models/audit.model';
import { AuditActorService } from './audit-actor.service';

describe('AuditActorService', () => {
  const baseUrl = `${environment.apiUrl}/users`;
  let service: AuditActorService;
  let httpMock: HttpTestingController;

  const user = (overrides: Partial<User> = {}): User => ({
    id: 7,
    name: 'Ana Souza',
    email: 'ana@empresa.com',
    role: 'ADMIN',
    active: true,
    companyId: 10,
    companyName: 'Empresa Teste',
    lastLoginAt: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(AuditActorService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista todos os usuários, inclusive os desativados', () => {
    let received: AuditActorOption[] | undefined;
    service.list().subscribe((actors) => (received = actors));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    // Sem `active=true`: esconder um usuário desativado tornaria as linhas que ele
    // gerou inalcançáveis pelo filtro, e a trilha é histórica.
    expect(request.request.params.has('active')).toBeFalse();

    request.flush([user(), user({ id: 8, name: 'Bruno', email: 'bruno@empresa.com', active: false })]);

    expect(received).toEqual([
      { id: 7, name: 'Ana Souza', email: 'ana@empresa.com', active: true },
      { id: 8, name: 'Bruno', email: 'bruno@empresa.com', active: false },
    ]);
  });
});
