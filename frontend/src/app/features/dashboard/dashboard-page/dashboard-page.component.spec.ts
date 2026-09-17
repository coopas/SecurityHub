import { HttpClientTestingModule, HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { NgChartsModule } from 'ng2-charts';

import { environment } from '../../../../environments/environment';
import { SharedModule } from '../../../shared/shared.module';
import {
  makeVulnerability,
  makeVulnerabilityPage,
} from '../../vulnerabilities/testing/vulnerability-test-utils';
import { DistributionChartComponent } from '../distribution-chart/distribution-chart.component';
import { RecentVulnerabilitiesComponent } from '../recent-vulnerabilities/recent-vulnerabilities.component';
import { SummaryCardsComponent } from '../summary-cards/summary-cards.component';
import {
  makeDashboardSummary,
  makeSeverityDistribution,
  makeStatusDistribution,
  makeTrend,
} from '../testing/dashboard-test-utils';
import { TrendChartComponent } from '../trend-chart/trend-chart.component';
import { DashboardPageComponent } from './dashboard-page.component';

describe('DashboardPageComponent', () => {
  const dashboardUrl = `${environment.apiUrl}/dashboard`;
  let fixture: ComponentFixture<DashboardPageComponent>;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [
        DashboardPageComponent,
        SummaryCardsComponent,
        DistributionChartComponent,
        TrendChartComponent,
        RecentVulnerabilitiesComponent,
      ],
      imports: [
        SharedModule,
        NgChartsModule,
        HttpClientTestingModule,
        RouterTestingModule,
        NoopAnimationsModule,
      ],
    });

    fixture = TestBed.createComponent(DashboardPageComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const trendRequest = (): TestRequest =>
    httpMock.expectOne((request) => request.url === `${dashboardUrl}/trend`);

  const recentRequest = (): TestRequest =>
    httpMock.expectOne((request) => request.url === `${environment.apiUrl}/vulnerabilities`);

  it('dispara as quatro chamadas do dashboard mais a listagem dos itens recentes', () => {
    fixture.detectChanges();

    httpMock.expectOne(`${dashboardUrl}/summary`).flush(makeDashboardSummary());
    httpMock
      .expectOne(`${dashboardUrl}/severity-distribution`)
      .flush(makeSeverityDistribution([3, 5, 2, 1]));
    httpMock
      .expectOne(`${dashboardUrl}/status-distribution`)
      .flush(makeStatusDistribution([4, 1, 5, 1]));

    const trend = trendRequest();
    expect(trend.request.params.get('days')).toBe('30');
    trend.flush(makeTrend([[2, 1]]));

    const recent = recentRequest();
    expect(recent.request.params.get('page')).toBe('0');
    expect(recent.request.params.get('size')).toBe('5');
    expect(recent.request.params.get('sort')).toBe('createdAt,desc');
    recent.flush(makeVulnerabilityPage([makeVulnerability()]));

    fixture.detectChanges();

    expect(element().querySelectorAll('.dashboard-card').length).toBe(7);
    expect(element().querySelectorAll('canvas').length).toBe(3);
    expect(element().querySelectorAll('table.sh-visually-hidden').length).toBe(3);
    expect(element().querySelectorAll('.dashboard-recent__item').length).toBe(1);
  });

  it('isola as regiões: uma tendência que falha não apaga os cards nem os outros painéis', () => {
    fixture.detectChanges();

    httpMock.expectOne(`${dashboardUrl}/summary`).flush(makeDashboardSummary());
    httpMock
      .expectOne(`${dashboardUrl}/severity-distribution`)
      .flush(makeSeverityDistribution([1, 0, 0, 0]));
    httpMock
      .expectOne(`${dashboardUrl}/status-distribution`)
      .flush(makeStatusDistribution([1, 0, 0, 0]));
    trendRequest().flush({ message: 'erro' }, { status: 500, statusText: 'Server Error' });
    recentRequest().flush(makeVulnerabilityPage([makeVulnerability()]));

    fixture.detectChanges();

    expect(element().querySelectorAll('.dashboard-card').length).toBe(7);
    expect(element().querySelectorAll('canvas').length).toBe(2);
    expect(element().querySelector('[data-testid="trend-state"]')?.textContent).toContain(
      'Não foi possível carregar os dados.',
    );
    expect(element().querySelectorAll('.dashboard-recent__item').length).toBe(1);
  });
});
