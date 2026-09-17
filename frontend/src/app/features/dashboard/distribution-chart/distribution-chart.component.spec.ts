import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NgChartsModule } from 'ng2-charts';
import { Subject, of, throwError } from 'rxjs';

import { THEME_STORAGE_KEY, ThemeService } from '../../../core/services/theme.service';
import { SharedModule } from '../../../shared/shared.module';
import { SeverityDistributionEntry } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import { makeSeverityDistribution, makeStatusDistribution } from '../testing/dashboard-test-utils';
import { DistributionChartComponent, DistributionKind } from './distribution-chart.component';

describe('DistributionChartComponent', () => {
  let fixture: ComponentFixture<DistributionChartComponent>;
  let component: DistributionChartComponent;
  let dashboardService: jasmine.SpyObj<DashboardService>;

  const themeColor = (token: string): string =>
    getComputedStyle(document.documentElement).getPropertyValue(token).trim();

  const setup = (kind: DistributionKind = 'severity'): void => {
    dashboardService = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'severityDistribution',
      'statusDistribution',
    ]);
    dashboardService.severityDistribution.and.returnValue(of(makeSeverityDistribution([3, 5, 2, 0])));
    dashboardService.statusDistribution.and.returnValue(of(makeStatusDistribution([4, 1, 5, 0])));

    TestBed.configureTestingModule({
      declarations: [DistributionChartComponent],
      imports: [SharedModule, NgChartsModule, NoopAnimationsModule],
      providers: [{ provide: DashboardService, useValue: dashboardService }],
    });

    fixture = TestBed.createComponent(DistributionChartComponent);
    component = fixture.componentInstance;
    component.kind = kind;
  };

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const tableValues = (): string[][] =>
    Array.from(element().querySelectorAll('table.sh-visually-hidden tbody tr')).map((row) => [
      row.querySelector('th')?.textContent?.trim() ?? '',
      row.querySelectorAll('td')[0]?.textContent?.trim() ?? '',
      row.querySelectorAll('td')[1]?.textContent?.trim() ?? '',
    ]);

  it('carrega apenas a distribuição por severidade quando kind é severity', () => {
    setup('severity');
    fixture.detectChanges();

    expect(dashboardService.severityDistribution).toHaveBeenCalledTimes(1);
    expect(dashboardService.statusDistribution).not.toHaveBeenCalled();
    expect(element().textContent).toContain('Vulnerabilidades por severidade');
  });

  it('carrega apenas a distribuição por status quando kind é status', () => {
    setup('status');
    fixture.detectChanges();

    expect(dashboardService.statusDistribution).toHaveBeenCalledTimes(1);
    expect(dashboardService.severityDistribution).not.toHaveBeenCalled();
    expect(element().textContent).toContain('Vulnerabilidades por status');
  });

  it('mapeia as contagens para as barras na ordem do enum, com as cores do tema', () => {
    setup('severity');
    fixture.detectChanges();

    expect(component.chartData.labels).toEqual(['Baixa', 'Média', 'Alta', 'Crítica']);
    expect(component.chartData.datasets[0].data).toEqual([3, 5, 2, 0]);
    expect(component.total).toBe(10);
    // The colors come out of the styles.scss tokens, the same ones as the chips in the lists.
    expect(component.chartData.datasets[0].backgroundColor).toEqual([
      themeColor('--sh-low'),
      themeColor('--sh-medium'),
      themeColor('--sh-high'),
      themeColor('--sh-critical'),
    ]);
  });

  it('usa as cores de status e mantém a categoria zerada no lugar', () => {
    setup('status');
    fixture.detectChanges();

    expect(component.chartData.labels).toEqual([
      'Aberta',
      'Em andamento',
      'Resolvida',
      'Risco aceito',
    ]);
    expect(component.chartData.datasets[0].data).toEqual([4, 1, 5, 0]);
    expect(component.chartData.datasets[0].backgroundColor).toEqual([
      themeColor('--sh-open'),
      themeColor('--sh-in-progress'),
      themeColor('--sh-resolved'),
      themeColor('--sh-accepted-risk'),
    ]);
    // Zero neither disappears from the legend nor loses its color.
    expect(component.categories[3]).toEqual(
      jasmine.objectContaining({ label: 'Risco aceito', count: 0 }),
    );
    expect(component.categories[3].color).toBe(themeColor('--sh-accepted-risk'));
  });

  /** Bars and legend come out of the same tokens, and both must follow the theme switch. */
  it('repinta as barras e a legenda com os tokens do novo tema', fakeAsync(() => {
    setup('severity');
    fixture.detectChanges();
    const theme = TestBed.inject(ThemeService);
    const before = component.categories[3].color;

    theme.set('dark');
    // The ThemeService emits before applying `data-theme` on the document: the repainter
    // defers the read by a microtask so it does not pick up the previous theme's tokens.
    tick();
    fixture.detectChanges();

    expect(component.categories[3].color).toBe(themeColor('--sh-critical'));
    expect(component.categories[3].color).not.toBe(before);
    expect(component.chartData.datasets[0].backgroundColor).toEqual([
      themeColor('--sh-low'),
      themeColor('--sh-medium'),
      themeColor('--sh-high'),
      themeColor('--sh-critical'),
    ]);
    // Repainting is only swapping colors: the numbers are not requested again.
    expect(dashboardService.severityDistribution).toHaveBeenCalledTimes(1);

    theme.set('light');
    tick();
    localStorage.removeItem(THEME_STORAGE_KEY);
  }));

  it('casa as contagens por chave, e não por posição da resposta', () => {
    setup('severity');
    const reversed: SeverityDistributionEntry[] = [
      { severity: 'CRITICAL', count: 1 },
      { severity: 'HIGH', count: 2 },
      { severity: 'MEDIUM', count: 3 },
      { severity: 'LOW', count: 4 },
    ];
    dashboardService.severityDistribution.and.returnValue(of(reversed));
    fixture.detectChanges();

    expect(component.chartData.datasets[0].data).toEqual([4, 3, 2, 1]);
  });

  it('repete os números do gráfico em uma tabela visualmente oculta', () => {
    setup('severity');
    fixture.detectChanges();

    const table = element().querySelector('table.sh-visually-hidden');
    expect(table).not.toBeNull();
    expect(tableValues()).toEqual([
      ['Baixa', '3', '30%'],
      ['Média', '5', '50%'],
      ['Alta', '2', '20%'],
      ['Crítica', '0', '0%'],
    ]);
    expect(table?.querySelector('tfoot td')?.textContent?.trim()).toBe('10');

    // The table is exactly what is in the chart, and not an approximate summary.
    expect(tableValues().map((row) => Number(row[1]))).toEqual(
      component.chartData.datasets[0].data as number[],
    );
    expect(tableValues().map((row) => row[0])).toEqual(component.chartData.labels as string[]);
  });

  it('dá ao canvas papel de imagem e um rótulo que resume o gráfico', () => {
    setup('severity');
    fixture.detectChanges();

    const canvas = element().querySelector('[data-testid="distribution-canvas-severity"]');
    expect(canvas?.getAttribute('role')).toBe('img');
    expect(canvas?.getAttribute('aria-label')).toContain('vulnerabilidades por severidade');
    expect(canvas?.getAttribute('aria-label')).toContain('10 no total');
  });

  it('esconde a legenda em HTML dos leitores de tela, que já leem a tabela', () => {
    setup('severity');
    fixture.detectChanges();

    const legend = element().querySelector('.dashboard-legend');
    expect(legend?.getAttribute('aria-hidden')).toBe('true');
    // Each item names the category in text: color is never the only indicator.
    expect(legend?.querySelectorAll('.dashboard-legend__item').length).toBe(4);
    expect(legend?.textContent).toContain('Crítica');
  });

  it('diz em palavras quando todas as categorias estão zeradas', () => {
    setup('severity');
    dashboardService.severityDistribution.and.returnValue(of(makeSeverityDistribution()));
    fixture.detectChanges();

    expect(component.state).toBe('empty');
    expect(element().querySelector('canvas')).toBeNull();
    expect(element().textContent).toContain('as quatro severidades estão zeradas');
  });

  it('mostra o estado de carregamento antes da resposta', () => {
    setup('severity');
    const pending = new Subject<SeverityDistributionEntry[]>();
    dashboardService.severityDistribution.and.returnValue(pending.asObservable());
    fixture.detectChanges();

    expect(component.state).toBe('loading');
    expect(element().querySelector('canvas')).toBeNull();

    pending.next(makeSeverityDistribution([1, 0, 0, 0]));
    pending.complete();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(element().querySelector('canvas')).not.toBeNull();
  });

  it('mostra erro com nova tentativa e recarrega ao repetir', () => {
    setup('severity');
    dashboardService.severityDistribution.and.returnValue(throwError(() => new Error('falhou')));
    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(element().querySelector('table.sh-visually-hidden')).toBeNull();

    dashboardService.severityDistribution.and.returnValue(of(makeSeverityDistribution([2, 0, 0, 0])));
    element()
      .querySelector<HTMLButtonElement>('[data-testid="distribution-state-severity"] button')
      ?.click();
    fixture.detectChanges();

    expect(dashboardService.severityDistribution).toHaveBeenCalledTimes(2);
    expect(component.state).toBeNull();
    expect(component.chartData.datasets[0].data).toEqual([2, 0, 0, 0]);
  });
});
