import { Component, OnDestroy, OnInit } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { Subject, takeUntil } from 'rxjs';

import { ThemeService } from '../../../core/services/theme.service';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DEFAULT_TREND_DAYS, TREND_DAYS_OPTIONS, Trend } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import { readChartChrome, readThemeColor, themeRepaints } from '../utils/theme-color.util';

/** One row of the textual table: the day already formatted and the two counts. */
export interface TrendRow {
  date: string;
  label: string;
  opened: number;
  resolved: number;
}

const OPENED_LABEL = 'Abertas';
const RESOLVED_LABEL = 'Resolvidas';

/**
 * Canvas options, rebuilt on every theme because the chrome also comes out of the tokens —
 * Chart.js does not read CSS and would fall back to the library's hard-coded grays, which
 * are invisible in the dark theme.
 */
function buildOptions(): ChartConfiguration<'line'>['options'] {
  const chrome = readChartChrome();

  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      // Native legend with text beside each marker: the series are never distinguished by
      // color alone. The marker has its own shape per series and the stroke differs too,
      // solid for opened and dashed for resolved.
      legend: {
        display: true,
        position: 'top',
        align: 'end',
        labels: {
          boxHeight: 8,
          usePointStyle: true,
          padding: 16,
          color: chrome.inkStrong,
        },
      },
      tooltip: {
        backgroundColor: chrome.surface,
        titleColor: chrome.inkStrong,
        bodyColor: chrome.inkStrong,
        borderColor: chrome.border,
        borderWidth: 1,
        padding: 10,
        usePointStyle: true,
      },
    },
    scales: {
      x: {
        grid: { display: false },
        border: { color: chrome.border },
        ticks: { color: chrome.ink, maxRotation: 0, autoSkipPadding: 16, font: { size: 12 } },
      },
      y: {
        beginAtZero: true,
        grid: { color: chrome.grid },
        border: { display: false },
        ticks: { color: chrome.ink, precision: 0, font: { size: 12 } },
      },
    },
  };
}

/**
 * Daily trend of openings and resolutions.
 *
 * The dates never go through `Date`: the backend sends `yyyy-MM-dd` in UTC, and converting
 * that to the browser's timezone would shift every point one day back in Brazil. Formatting
 * by string slicing keeps the axis, the table and the period in the title talking about the
 * same day.
 */
@Component({
  selector: 'app-trend-chart',
  templateUrl: './trend-chart.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class TrendChartComponent implements OnInit, OnDestroy {
  readonly daysOptions = TREND_DAYS_OPTIONS;

  /** The requested window; the effective one is always the one that came back. */
  selectedDays = DEFAULT_TREND_DAYS;

  state: ViewState | null = 'loading';
  trend: Trend | null = null;
  rows: TrendRow[] = [];
  totalOpened = 0;
  totalResolved = 0;

  chartData: ChartConfiguration<'line'>['data'] = { labels: [], datasets: [] };
  chartOptions: ChartConfiguration<'line'>['options'] = buildOptions();

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly dashboardService: DashboardService,
    private readonly themeService: ThemeService,
  ) {}

  /** Period label taken from the response: `days` may have been clamped by the server. */
  get periodLabel(): string {
    if (!this.trend) {
      return '';
    }
    return `Últimos ${this.trend.days} dias — de ${this.formatDate(this.trend.from)} a ${this.formatDate(this.trend.to)}`;
  }

  get emptyMessage(): string {
    const days = this.trend?.days ?? this.selectedDays;
    return `Nenhuma vulnerabilidade foi aberta ou resolvida nos últimos ${days} dias.`;
  }

  get chartLabel(): string {
    const days = this.trend?.days ?? this.selectedDays;
    return `Gráfico de linhas: vulnerabilidades abertas e resolvidas por dia nos últimos ${days} dias, ${this.totalOpened} abertas e ${this.totalResolved} resolvidas no período. Os valores diários estão na tabela seguinte.`;
  }

  ngOnInit(): void {
    this.load();

    // The two series' colors are pixels on the canvas, not CSS: without a repaint, the
    // light theme's line would stay drawn over the dark background.
    themeRepaints(this.themeService.theme$)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.repaint());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.state = 'loading';
    this.dashboardService.trend(this.selectedDays).subscribe({
      next: (trend) => this.apply(trend),
      error: () => {
        this.trend = null;
        this.rows = [];
        this.totalOpened = 0;
        this.totalResolved = 0;
        this.chartData = { labels: [], datasets: [] };
        this.state = 'error';
      },
    });
  }

  onDaysChange(days: number): void {
    this.selectedDays = days;
    this.load();
  }

  trackByDate(_index: number, row: TrendRow): string {
    return row.date;
  }

  /** `yyyy-MM-dd` → `dd/MM/yyyy`, without `Date` and therefore without a timezone shift. */
  formatDate(date: string): string {
    const [year, month, day] = date.split('-');
    return day && month && year ? `${day}/${month}/${year}` : date;
  }

  /** Horizontal axis: day and month only, which is what fits across 90 points. */
  private formatAxisDate(date: string): string {
    const [, month, day] = date.split('-');
    return day && month ? `${day}/${month}` : date;
  }

  /** Same series, new tokens. Nothing is reloaded: only the colors changed. */
  private repaint(): void {
    this.chartOptions = buildOptions();
    if (this.trend) {
      this.apply(this.trend);
    }
  }

  private apply(trend: Trend): void {
    this.trend = trend;
    const points = trend.points ?? [];

    this.rows = points.map((point) => ({
      date: point.date,
      label: this.formatDate(point.date),
      opened: point.opened,
      resolved: point.resolved,
    }));
    this.totalOpened = points.reduce((sum, point) => sum + point.opened, 0);
    this.totalResolved = points.reduce((sum, point) => sum + point.resolved, 0);

    const openedColor = readThemeColor('--sh-open', '#b91c1c');
    const resolvedColor = readThemeColor('--sh-resolved', '#15803d');

    this.chartData = {
      labels: points.map((point) => this.formatAxisDate(point.date)),
      datasets: [
        {
          label: OPENED_LABEL,
          data: points.map((point) => point.opened),
          borderColor: openedColor,
          backgroundColor: openedColor,
          pointBackgroundColor: openedColor,
          pointStyle: 'circle',
          pointRadius: 2,
          pointHoverRadius: 5,
          borderWidth: 2,
          tension: 0.25,
          fill: false,
        },
        {
          label: RESOLVED_LABEL,
          data: points.map((point) => point.resolved),
          borderColor: resolvedColor,
          backgroundColor: resolvedColor,
          pointBackgroundColor: resolvedColor,
          // Diamond against circle: the marker shape separates the series in the legend and
          // in the tooltip even for whoever cannot tell the two colors apart.
          pointStyle: 'rectRot',
          pointRadius: 2,
          pointHoverRadius: 5,
          borderWidth: 2,
          // A different stroke for the second series: whoever cannot tell the two colors
          // apart still separates the lines.
          borderDash: [6, 4],
          tension: 0.25,
          fill: false,
        },
      ],
    };

    // The backend returns the complete series, with zeros on the idle days: "empty" is the
    // whole series zeroed, and not the absence of points.
    this.state = this.totalOpened === 0 && this.totalResolved === 0 ? 'empty' : null;
  }
}
