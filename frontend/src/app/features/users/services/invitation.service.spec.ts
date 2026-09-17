import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { makeAuthResponse } from '../../../core/testing/auth-test-utils';
import { makeInvitation, makeInvitationPreview } from '../testing/user-test-utils';
import { InvitationService } from './invitation.service';

describe('InvitationService', () => {
  let service: InvitationService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.apiUrl}/invitations`;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(InvitationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista os convites da empresa', (done) => {
    service.list().subscribe((invitations) => {
      expect(invitations[0].email).toBe('bruno@empresa.com');
      done();
    });

    httpMock.expectOne(baseUrl).flush([makeInvitation()]);
  });

  it('cria o convite sem mandar a empresa no corpo', (done) => {
    service
      .create({ name: 'Bruno Lima', email: 'bruno@empresa.com', role: 'ANALYST' })
      .subscribe(() => done());

    const request = httpMock.expectOne(baseUrl);
    expect(request.request.method).toBe('POST');
    // A empresa é sempre a do autenticado; um campo aqui seria ignorado pelo backend.
    expect(request.request.body).toEqual({
      name: 'Bruno Lima',
      email: 'bruno@empresa.com',
      role: 'ANALYST',
    });
    request.flush(makeInvitation());
  });

  it('revoga pelo identificador', (done) => {
    service.revoke(5).subscribe(() => done());

    const request = httpMock.expectOne(`${baseUrl}/5`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('lê a prévia pública passando o token como parâmetro', (done) => {
    service.preview('token-do-convite').subscribe((preview) => {
      expect(preview.companyName).toBe('Empresa Teste');
      done();
    });

    const request = httpMock.expectOne(`${baseUrl}/accept?token=token-do-convite`);
    expect(request.request.method).toBe('GET');
    request.flush(makeInvitationPreview());
  });

  it('aceita o convite e devolve a sessão pronta', (done) => {
    service.accept({ token: 'token-do-convite', password: 'senha-super-secreta' }).subscribe(
      (response) => {
        expect(response.refreshToken).toBeTruthy();
        done();
      },
    );

    const request = httpMock.expectOne(`${baseUrl}/accept`);
    expect(request.request.method).toBe('POST');
    request.flush(makeAuthResponse('ANALYST'));
  });
});
