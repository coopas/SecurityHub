import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NgChartsModule } from 'ng2-charts';
import { Subject, of, throwError } from 'rxjs';

import { THEME_STORAGE_KEY, ThemeService } from '../../../core/services/theme.service';
import { SharedModule } from '../../../shared/shared.module';
import { DEFAULT_TREND_DAYS, Trend } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import { makeTrend } from '../testing/dashboard-test-utils';
import { TrendChartComponent } from './trend-chart.component';

describe('TrendChartComponent', () => {
  let fixture: ComponentFixture<TrendChartComponent>;
  let component: TrendChartComponent;
  let dashboardService: jasmine.SpyObj<DashboardService>;

  const themeColor = (token: string): string =>
    getComputedStyle(document.documentElement).getPropertyValue(token).trim();

  const trend = makeTrend(
    [
      [2, 0],
      [0, 3],
      [1, 1],
    ],
    '2026-03-30',
  );

  beforeEach(() => {
    dashboardService = jasmine.createSpyObj<DashboardService>('DashboardService', ['trend']);
    dashboardService.trend.and.returnValue(of(trend));

    TestBed.configureTestingModule({
      declarations: [TrendChartComponent],
      imports: [SharedModule, NgChartsModule, NoopAnimationsModule],
      providers: [{ provide: DashboardService, useValue: dashboardService }],
    });

    fixture = TestBed.createComponent(TrendChartComponent);
    component = fixture.componentInstance;
  });

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const tableRows = (): string[][] =>
    Array.from(element().querySelectorAll('[data-testid="trend-table"] tbody tr')).map((row) => [
      row.querySelector('th')?.textContent?.trim() ?? '',
      row.querySelectorAll('td')[0]?.textContent?.trim() ?? '',
      row.querySelectorAll('td')[1]?.textContent?.trim() ?? '',
    ]);

  it('pede a janela padrão de 30 dias ao carregar', () => {
    fixture.detectChanges();

    expect(dashboardService.trend).toHaveBeenCalledOnceWith(DEFAULT_TREND_DAYS);
  });

  it('mapeia as duas séries com as cores do tema e traços distintos', () => {
    fixture.detectChanges();

    expect(component.chartData.labels).toEqual(['30/03', '31/03', '01/04']);
    expect(component.chartData.datasets.length).toBe(2);

    const [opened, resolved] = component.chartData.datasets;
    expect(opened.label).toBe('Abertas');
    expect(opened.data).toEqual([2, 0, 1]);
    expect(opened.borderColor).toBe(themeColor('--sh-open'));
    expect(resolved.label).toBe('Resolvidas');
    expect(resolved.data).toEqual([0, 3, 1]);
    expect(resolved.borderColor).toBe(themeColor('--sh-resolved'));
    // The second series is dashed as well: the lines are not told apart by color alone.
    expect(resolved.borderDash).toEqual([6, 4]);
  });

  /**
   * The canvas is a bitmap: the color becomes a pixel at drawing time and does not follow a
   * token switch the way CSS would. Without a repaint, the light theme's line would stay
   * drawn over the dark background.
   */
  it('repinta as séries com os tokens do novo tema quando o tema muda', fakeAsync(() => {
    fixture.detectChanges();
    const theme = TestBed.inject(ThemeService);
    const before = component.chartData.datasets[0].borderColor;

    theme.set('dark');
    // The ThemeService emits before writing `data-theme`; the repainter waits a microtask
    // precisely so it reads the tokens already swapped. Without this `tick` — and without
    // the wait in the component — the color read would still be the outgoing theme's.
    tick();
    fixture.detectChanges();

    expect(component.chartData.datasets[0].borderColor).toBe(themeColor('--sh-open'));
    expect(component.chartData.datasets[0].borderColor).not.toBe(before);
    expect(component.chartData.datasets[1].borderColor).toBe(themeColor('--sh-resolved'));
    // A theme switch is no reason to ask the server for the series again.
    expect(dashboardService.trend).toHaveBeenCalledTimes(1);

    theme.set('light');
    tick();
    localStorage.removeItem(THEME_STORAGE_KEY);
  }));

  it('repete a série em uma tabela visualmente oculta, com os totais', () => {
    fixture.detectChanges();

    expect(tableRows()).toEqual([
      ['30/03/2026', '2', '0'],
      ['31/03/2026', '0', '3'],
      ['01/04/2026', '1', '1'],
    ]);

    // The same numbers as the chart, day by day.
    expect(tableRows().map((row) => Number(row[1]))).toEqual(
      component.chartData.datasets[0].data as number[],
    );
    expect(tableRows().map((row) => Number(row[2]))).toEqual(
      component.chartData.datasets[1].data as number[],
    );

    const totals = element().querySelectorAll('[data-testid="trend-table"] tfoot td');
    expect(totals[0].textContent?.trim()).toBe('3');
    expect(totals[1].textContent?.trim()).toBe('4');
  });

  it('formata as datas sem converter fuso, mantendo o dia que o servidor mandou', () => {
    fixture.detectChanges();

    // `2026-03-30` becomes 30/03 even in negative timezones: the formatting slices the string.
    expect(component.formatDate('2026-03-30')).toBe('30/03/2026');
    expect(element().querySelector('[data-testid="trend-period"]')?.textContent).toContain(
      'de 30/03/2026 a 01/04/2026',
    );
  });

  it('rotula o período com o days que voltou, e não com o que foi pedido', () => {
    dashboardService.trend.and.returnValue(of({ ...trend, days: 90 }));
    fixture.detectChanges();

    expect(component.trend?.days).toBe(90);
    expect(element().querySelector('[data-testid="trend-period"]')?.textContent).toContain(
      'Últimos 90 dias',
    );
  });

  it('recarrega ao trocar a janela pelo seletor', () => {
    fixture.detectChanges();

    const buttons = element().querySelectorAll<HTMLButtonElement>(
      '[data-testid="trend-days"] button',
    );
    expect(buttons.length).toBe(3);
    buttons[2].click();
    fixture.detectChanges();

    expect(component.selectedDays).toBe(90);
    expect(dashboardService.trend).toHaveBeenCalledWith(90);
    expect(dashboardService.trend).toHaveBeenCalledTimes(2);
  });

  it('dá ao canvas papel de imagem e um rótulo com o resumo do período', () => {
    fixture.detectChanges();

    const canvas = element().querySelector('[data-testid="trend-canvas"]');
    expect(canvas?.getAttribute('role')).toBe('img');
    expect(canvas?.getAttribute('aria-label')).toContain('3 abertas e 4 resolvidas');
  });

  it('diz em palavras quando a série inteira está zerada', () => {
    dashboardService.trend.and.returnValue(
      of(
        makeTrend([
          [0, 0],
          [0, 0],
        ]),
      ),
    );
    fixture.detectChanges();

    // The backend returns every day with zeros: empty is the series with no movement.
    expect(component.state).toBe('empty');
    expect(element().querySelector('canvas')).toBeNull();
    expect(element().textContent).toContain('Nenhuma vulnerabilidade foi aberta ou resolvida');
  });

  it('mostra o estado de carregamento antes da resposta', () => {
    const pending = new Subject<Trend>();
    dashboardService.trend.and.returnValue(pending.asObservable());
    fixture.detectChanges();

    expect(component.state).toBe('loading');
    expect(element().querySelector('canvas')).toBeNull();

    pending.next(trend);
    pending.complete();
    fixture.detectChanges();

    expect(component.state).toBeNull();
    expect(element().querySelector('canvas')).not.toBeNull();
  });

  it('mostra erro com nova tentativa e recarrega ao repetir', () => {
    dashboardService.trend.and.returnValue(throwError(() => new Error('falhou')));
    fixture.detectChanges();

    expect(component.state).toBe('error');
    expect(element().querySelector('[data-testid="trend-table"]')).toBeNull();

    dashboardService.trend.and.returnValue(of(trend));
    element().querySelector<HTMLButtonElement>('[data-testid="trend-state"] button')?.click();
    fixture.detectChanges();

    expect(dashboardService.trend).toHaveBeenCalledTimes(2);
    expect(component.state).toBeNull();
    expect(tableRows().length).toBe(3);
  });
});
