import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import { PageResponse } from '../../../core/models';
import { AuditLog } from '../models/audit.model';
import { makeAuditLog, makeAuditPage } from '../testing/audit-test-utils';
import { toCivilDate } from '../utils/audit-date.util';
import { AuditService } from './audit.service';

describe('AuditService', () => {
  const baseUrl = `${environment.apiUrl}/audit-logs`;
  let service: AuditService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(AuditService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lista enviando page, size e sort, sem filtros vazios', () => {
    let received: PageResponse<AuditLog> | undefined;
    service
      .list({ page: 2, size: 50, sort: 'action,asc' })
      .subscribe((page) => (received = page));

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('50');
    expect(request.request.params.get('sort')).toBe('action,asc');
    expect(request.request.params.has('entityType')).toBeFalse();
    expect(request.request.params.has('actorId')).toBeFalse();
    expect(request.request.params.has('action')).toBeFalse();
    expect(request.request.params.has('from')).toBeFalse();
    expect(request.request.params.has('to')).toBeFalse();

    const page = makeAuditPage([makeAuditLog()]);
    request.flush(page);
    expect(received).toEqual(page);
  });

  it('inclui entityType, actorId e action quando preenchidos', () => {
    service
      .list({
        page: 0,
        size: 20,
        sort: 'createdAt,desc',
        entityType: 'Vulnerability',
        actorId: 7,
        action: 'STATUS_CHANGE',
      })
      .subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.params.get('entityType')).toBe('Vulnerability');
    expect(request.request.params.get('actorId')).toBe('7');
    expect(request.request.params.get('action')).toBe('STATUS_CHANGE');
    request.flush(makeAuditPage([]));
  });

  it('converte as datas para instantes ISO, com o "até" cobrindo o dia inteiro', () => {
    service
      .list({ page: 0, size: 20, sort: 'createdAt,desc', from: '2026-09-01', to: '2026-09-17' })
      .subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    const from = new Date(request.request.params.get('from') as string);
    const to = new Date(request.request.params.get('to') as string);

    expect(request.request.params.get('from')).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/,
    );
    expect(toCivilDate(from)).toBe('2026-09-01');
    expect(from.getHours()).toBe(0);
    expect(from.getMinutes()).toBe(0);

    // The critical point: `AuditSpecifications` compares `createdAt <= to`. Midnight of
    // the 17th would hide the whole 17th, which is exactly what the user asked for.
    expect(toCivilDate(to)).toBe('2026-09-17');
    expect(to.getHours()).toBe(23);
    expect(to.getMinutes()).toBe(59);
    expect(to.getSeconds()).toBe(59);

    request.flush(makeAuditPage([]));
  });

  it('descarta datas inválidas em vez de mandar lixo ao servidor', () => {
    service
      .list({ page: 0, size: 20, sort: 'createdAt,desc', from: '2026-02-31', to: 'ontem' })
      .subscribe();

    const request = httpMock.expectOne((candidate) => candidate.url === baseUrl);
    expect(request.request.params.has('from')).toBeFalse();
    expect(request.request.params.has('to')).toBeFalse();
    request.flush(makeAuditPage([]));
  });

  it('expõe apenas leitura: não há operação de escrita na trilha', () => {
    const writeVerbs = ['create', 'update', 'delete', 'patch', 'save'];

    for (const verb of writeVerbs) {
      expect((service as unknown as Record<string, unknown>)[verb]).toBeUndefined();
    }
  });
});
