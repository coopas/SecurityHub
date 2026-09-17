import { HttpClientTestingModule, HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { RouterTestingModule } from '@angular/router/testing';
import { NgChartsModule } from 'ng2-charts';

import { environment } from '../../../../environments/environment';
import { Role } from '../../../core/models';
import { ACCESS_TOKEN_STORAGE_KEY, CURRENT_USER_STORAGE_KEY } from '../../../core/services/auth.service';
import { makeJwt, makeUser } from '../../../core/testing/auth-test-utils';
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
  let component: DashboardPageComponent;
  let httpMock: HttpTestingController;

  /** With no role there is no session, which is the state of this screen's layout tests. */
  const setup = (role?: Role): void => {
    if (role) {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, makeJwt(3600));
      localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(makeUser(role)));
    }

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
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  };

  /** The dashboard's four calls plus the recent items list, which always fire. */
  const flushDashboard = (): void => {
    httpMock.expectOne(`${dashboardUrl}/summary`).flush(makeDashboardSummary());
    httpMock
      .expectOne(`${dashboardUrl}/severity-distribution`)
      .flush(makeSeverityDistribution([1, 0, 0, 0]));
    httpMock
      .expectOne(`${dashboardUrl}/status-distribution`)
      .flush(makeStatusDistribution([1, 0, 0, 0]));
    trendRequest().flush(makeTrend([[1, 0]]));
    recentRequest().flush(makeVulnerabilityPage([makeVulnerability()]));
  };

  beforeEach(() => localStorage.clear());

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const trendRequest = (): TestRequest =>
    httpMock.expectOne((request) => request.url === `${dashboardUrl}/trend`);

  const recentRequest = (): TestRequest =>
    httpMock.expectOne((request) => request.url === `${environment.apiUrl}/vulnerabilities`);

  it('dispara as quatro chamadas do dashboard mais a listagem dos itens recentes', () => {
    setup();
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
    setup();
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

  it('oferece o relatório executivo a ADMIN e ANALYST, e o baixa pelo nome do servidor', () => {
    setup('ANALYST');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');
    spyOn(URL, 'createObjectURL').and.returnValue('blob:objeto');
    spyOn(URL, 'revokeObjectURL');
    fixture.detectChanges();
    flushDashboard();
    fixture.detectChanges();

    const button = element().querySelector<HTMLButtonElement>('[data-testid="report-export"]');
    expect(button).not.toBeNull();
    button?.click();

    const request = httpMock.expectOne(`${environment.apiUrl}/reports/executive`);
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['%PDF-1.4'], { type: 'application/pdf' }), {
      headers: { 'Content-Disposition': "attachment; filename*=UTF-8''relatorio-executivo.pdf" },
    });
    fixture.detectChanges();

    expect(click).toHaveBeenCalledTimes(1);
    expect(component.exportError).toBeNull();
    expect(component.exporting).toBeFalse();
  });

  it('esconde o relatório do VIEWER, que o servidor recusaria', () => {
    setup('VIEWER');
    fixture.detectChanges();
    flushDashboard();
    fixture.detectChanges();

    expect(component.canExportReport).toBeFalse();
    expect(element().querySelector('[data-testid="report-export"]')).toBeNull();
  });

  it('esconde o relatório do DEVELOPER, que o servidor também recusaria', () => {
    setup('DEVELOPER');
    fixture.detectChanges();
    flushDashboard();
    fixture.detectChanges();

    expect(component.canExportReport).toBeFalse();
    expect(element().querySelector('[data-testid="report-export"]')).toBeNull();
  });

  it('mostra a falha do relatório sem apagar painel algum', () => {
    setup('ADMIN');
    fixture.detectChanges();
    flushDashboard();
    fixture.detectChanges();

    element().querySelector<HTMLButtonElement>('[data-testid="report-export"]')?.click();
    httpMock
      .expectOne(`${environment.apiUrl}/reports/executive`)
      .flush(new Blob(['erro'], { type: 'application/json' }), {
        status: 500,
        statusText: 'Server Error',
      });
    fixture.detectChanges();

    expect(component.exportError).toBe('Não foi possível gerar o relatório.');
    expect(element().querySelectorAll('.dashboard-card').length).toBe(7);
  });
});
