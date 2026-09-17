import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { makeUser } from '../../../core/testing/auth-test-utils';
import { UserSummary } from '../models/vulnerability.model';
import { UserOptionService } from './user-option.service';

describe('UserOptionService', () => {
  const baseUrl = `${environment.apiUrl}/users`;
  let service: UserOptionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(UserOptionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('pede somente usuários ativos e reduz a resposta ao resumo', () => {
    let received: UserSummary[] | undefined;
    service.listActive().subscribe((users) => (received = users));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('active')).toBe('true');

    request.flush([makeUser('ANALYST')]);

    expect(received).toEqual([
      { id: 1, name: 'Ana Souza', email: 'ana@empresa.com', role: 'ANALYST' },
    ]);
  });
});
