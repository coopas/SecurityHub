import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../../environments/environment';
import {
  DashboardSummary,
  SeverityDistributionEntry,
  StatusDistributionEntry,
  Trend,
} from '../models/dashboard.model';
import {
  makeDashboardSummary,
  makeSeverityDistribution,
  makeStatusDistribution,
  makeTrend,
} from '../testing/dashboard-test-utils';
import { DashboardService } from './dashboard.service';

describe('DashboardService', () => {
  const baseUrl = `${environment.apiUrl}/dashboard`;
  let service: DashboardService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule] });
    service = TestBed.inject(DashboardService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('busca o resumo em GET /dashboard/summary', () => {
    let received: DashboardSummary | undefined;
    service.summary().subscribe((summary) => (received = summary));

    const request = httpMock.expectOne(`${baseUrl}/summary`);
    expect(request.request.method).toBe('GET');

    const summary = makeDashboardSummary();
    request.flush(summary);
    expect(received).toEqual(summary);
  });

  it('lê a distribuição por severidade como array puro, com as zeradas', () => {
    let received: SeverityDistributionEntry[] | undefined;
    service.severityDistribution().subscribe((entries) => (received = entries));

    const request = httpMock.expectOne(`${baseUrl}/severity-distribution`);
    expect(request.request.method).toBe('GET');

    request.flush(makeSeverityDistribution([3, 0, 2, 1]));
    expect(received?.length).toBe(4);
    expect(received?.map((entry) => entry.severity)).toEqual([
      'LOW',
      'MEDIUM',
      'HIGH',
      'CRITICAL',
    ]);
    expect(received?.map((entry) => entry.count)).toEqual([3, 0, 2, 1]);
  });

  it('lê a distribuição por status como array puro, com os zerados', () => {
    let received: StatusDistributionEntry[] | undefined;
    service.statusDistribution().subscribe((entries) => (received = entries));

    const request = httpMock.expectOne(`${baseUrl}/status-distribution`);
    expect(request.request.method).toBe('GET');

    request.flush(makeStatusDistribution([4, 1, 0, 0]));
    expect(received?.map((entry) => entry.status)).toEqual([
      'OPEN',
      'IN_PROGRESS',
      'RESOLVED',
      'ACCEPTED_RISK',
    ]);
    expect(received?.map((entry) => entry.count)).toEqual([4, 1, 0, 0]);
  });

  it('envia days na tendência e devolve a janela efetiva da resposta', () => {
    let received: Trend | undefined;
    service.trend(365).subscribe((trend) => (received = trend));

    const request = httpMock.expectOne((candidate) => candidate.url === `${baseUrl}/trend`);
    expect(request.request.method).toBe('GET');
    // O valor pedido viaja como está: o limite [1, 90] é do servidor, e é o eco dele que
    // a tela usa.
    expect(request.request.params.get('days')).toBe('365');

    const trend = makeTrend([
      [1, 0],
      [0, 2],
    ]);
    request.flush({ ...trend, days: 90 });
    expect(received?.days).toBe(90);
    expect(received?.points.length).toBe(2);
  });
});
