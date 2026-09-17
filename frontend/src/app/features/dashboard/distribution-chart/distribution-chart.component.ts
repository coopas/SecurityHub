import { Component, Input, OnDestroy, OnInit } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { Observable, Subject, map, takeUntil } from 'rxjs';

import { ThemeService } from '../../../core/services/theme.service';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  SEVERITIES,
  SEVERITY_LABELS,
  VULNERABILITY_STATUSES,
  VULNERABILITY_STATUS_LABELS,
} from '../../vulnerabilities/models/vulnerability.model';
import { DashboardService } from '../services/dashboard.service';
import { readChartChrome, readThemeColor, themeRepaints } from '../utils/theme-color.util';

export type DistributionKind = 'severity' | 'status';

/** One bar: label, count, share of the total and the theme color. */
export interface DistributionCategory {
  key: string;
  label: string;
  count: number;
  color: string;
  percent: number;
}

interface CategoryDefinition {
  key: string;
  label: string;
  token: string;
  fallback: string;
}

/**
 * Fixed definitions per kind, in the declaration order of the backend enum — which is the
 * order the responses arrive in. The counts are matched by key and not by position: the
 * screen stays correct if the response order changes, and a missing category becomes zero
 * instead of disappearing from the legend.
 */
// The fallback only comes into play if the token does not resolve — in practice, in a test
// that mounts the component without the global stylesheet. It is always the light theme
// value, because a constant has no way of knowing the theme; the real path goes through the
// token and switches along with it.
const DEFINITIONS: Readonly<Record<DistributionKind, readonly CategoryDefinition[]>> = {
  severity: SEVERITIES.map((severity) => ({
    key: severity,
    label: SEVERITY_LABELS[severity],
    token: `--sh-${severity.toLowerCase()}`,
    fallback: '#475569',
  })),
  status: VULNERABILITY_STATUSES.map((status) => ({
    key: status,
    label: VULNERABILITY_STATUS_LABELS[status],
    token: `--sh-${status.toLowerCase().replace('_', '-')}`,
    fallback: '#475569',
  })),
};

const TITLES: Readonly<Record<DistributionKind, string>> = {
  severity: 'Vulnerabilidades por severidade',
  status: 'Vulnerabilidades por status',
};

const AXIS_TITLES: Readonly<Record<DistributionKind, string>> = {
  severity: 'Severidade',
  status: 'Status',
};

/**
 * Canvas options, rebuilt on every theme because the chrome is tokenized too.
 *
 * `maintainAspectRatio: false` with a fixed height in CSS is what keeps the canvas from
 * blowing out the grid column on narrow screens: without it Chart.js keeps the ratio and
 * grows beyond the container.
 */
function buildOptions(): ChartConfiguration<'bar'>['options'] {
  const chrome = readChartChrome();

  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      // Chart.js's native legend would describe the dataset, not the categories; the labels
      // live on the axis and in the HTML legend, which survive any color blindness.
      legend: { display: false },
      tooltip: {
        backgroundColor: chrome.surface,
        titleColor: chrome.inkStrong,
        bodyColor: chrome.inkStrong,
        borderColor: chrome.border,
        borderWidth: 1,
        padding: 10,
        displayColors: false,
      },
    },
    scales: {
      x: {
        grid: { display: false },
        border: { color: chrome.border },
        ticks: { color: chrome.ink, font: { size: 12 } },
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
 * Distribution by severity or by status. A single component because both responses have
 * exactly the same shape (category + count) and the drawing is the same; what changes is the
 * endpoint, the labels and the colors, all tabulated above.
 *
 * An independent async region: it loads, fails and is reloaded on its own, without affecting
 * the cards or the other charts.
 */
@Component({
  selector: 'app-distribution-chart',
  templateUrl: './distribution-chart.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class DistributionChartComponent implements OnInit, OnDestroy {
  @Input({ required: true }) kind: DistributionKind = 'severity';

  state: ViewState | null = 'loading';
  categories: DistributionCategory[] = [];
  total = 0;

  chartData: ChartConfiguration<'bar'>['data'] = { labels: [], datasets: [] };
  chartOptions: ChartConfiguration<'bar'>['options'] = buildOptions();

  /** Last accepted response, kept so a repaint does not ask for the numbers again. */
  private counts: Map<string, number> | null = null;

  private readonly destroy$ = new Subject<void>();

  constructor(
    private readonly dashboardService: DashboardService,
    private readonly themeService: ThemeService,
  ) {}

  get title(): string {
    return TITLES[this.kind];
  }

  get axisTitle(): string {
    return AXIS_TITLES[this.kind];
  }

  get emptyMessage(): string {
    return this.kind === 'severity'
      ? 'Nenhuma vulnerabilidade cadastrada ainda: as quatro severidades estão zeradas.'
      : 'Nenhuma vulnerabilidade cadastrada ainda: os quatro status estão zerados.';
  }

  /** Short summary on the canvas; the full numbers live in the table next to it. */
  get chartLabel(): string {
    return `Gráfico de barras: ${this.title.toLowerCase()}, ${this.total} no total. Os valores estão na tabela seguinte.`;
  }

  ngOnInit(): void {
    this.load();

    // Switching theme switches the tokens, and the canvas does not repaint itself: the
    // colors become pixels at drawing time. Here the chart is rebuilt with the new tokens,
    // without a second trip to the server.
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
    this.fetch().subscribe({
      next: (counts) => {
        this.apply(counts);
      },
      error: () => {
        this.counts = null;
        this.categories = [];
        this.total = 0;
        this.chartData = { labels: [], datasets: [] };
        this.state = 'error';
      },
    });
  }

  private fetch(): Observable<Map<string, number>> {
    if (this.kind === 'severity') {
      return this.dashboardService
        .severityDistribution()
        .pipe(map((entries) => new Map(entries.map((entry) => [entry.severity, entry.count]))));
    }
    return this.dashboardService
      .statusDistribution()
      .pipe(map((entries) => new Map(entries.map((entry) => [entry.status, entry.count]))));
  }

  /** Same numbers, new tokens. Nothing is reloaded: only the colors changed. */
  private repaint(): void {
    this.chartOptions = buildOptions();
    if (this.counts) {
      this.apply(this.counts);
    }
  }

  /**
   * "Empty" here means "all zeros", and not "no rows": the backend always returns the four
   * categories. In that case the screen says so in words instead of drawing a blank canvas
   * that nobody can interpret.
   */
  private apply(counts: Map<string, number>): void {
    this.counts = counts;

    const definitions = DEFINITIONS[this.kind];
    const values = definitions.map((definition) => counts.get(definition.key) ?? 0);
    this.total = values.reduce((sum, value) => sum + value, 0);

    this.categories = definitions.map((definition, index) => ({
      key: definition.key,
      label: definition.label,
      count: values[index],
      color: readThemeColor(definition.token, definition.fallback),
      percent: this.total === 0 ? 0 : Math.round((values[index] / this.total) * 100),
    }));

    this.chartData = {
      labels: this.categories.map((category) => category.label),
      datasets: [
        {
          label: this.axisTitle,
          data: values,
          // An array of colors, one per bar: the zeroed category keeps its color and its
          // position, so the legend does not reorder itself between two loads.
          backgroundColor: this.categories.map((category) => category.color),
          borderColor: this.categories.map((category) => category.color),
          borderWidth: 1,
          borderRadius: 4,
          maxBarThickness: 56,
        },
      ],
    };

    this.state = this.total === 0 ? 'empty' : null;
  }
}
