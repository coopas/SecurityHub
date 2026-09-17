import { Component, OnDestroy, OnInit } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { Subject, takeUntil } from 'rxjs';

import { ThemeService } from '../../../core/services/theme.service';
import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DEFAULT_TREND_DAYS, TREND_DAYS_OPTIONS, Trend } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import { readChartChrome, readThemeColor, themeRepaints } from '../utils/theme-color.util';

/** Uma linha da tabela textual: o dia já formatado e as duas contagens. */
export interface TrendRow {
  date: string;
  label: string;
  opened: number;
  resolved: number;
}

const OPENED_LABEL = 'Abertas';
const RESOLVED_LABEL = 'Resolvidas';

/**
 * Opções do canvas, remontadas a cada tema porque o cromo também sai dos tokens — o
 * Chart.js não lê CSS e cairia nos cinzas fixos da biblioteca, invisíveis no escuro.
 */
function buildOptions(): ChartConfiguration<'line'>['options'] {
  const chrome = readChartChrome();

  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      // Legenda nativa com texto ao lado de cada marcador: as séries nunca são distinguidas
      // apenas pela cor. O marcador tem forma própria por série e o traçado também difere,
      // contínuo para abertas e tracejado para resolvidas.
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
 * Tendência diária de aberturas e resoluções.
 *
 * As datas nunca passam por `Date`: o backend manda `yyyy-MM-dd` em UTC e converter isso
 * para o fuso do navegador deslocaria cada ponto um dia para trás no Brasil. Formatar por
 * recorte de string mantém o eixo, a tabela e o período do título falando do mesmo dia.
 */
@Component({
  selector: 'app-trend-chart',
  templateUrl: './trend-chart.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class TrendChartComponent implements OnInit, OnDestroy {
  readonly daysOptions = TREND_DAYS_OPTIONS;

  /** Janela pedida; a efetiva é sempre a que voltou na resposta. */
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

  /** Rótulo do período tirado da resposta: `days` pode ter sido limitado pelo servidor. */
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

    // As cores das duas séries são pixels no canvas, não CSS: sem repintar, a linha do
    // tema claro continuaria desenhada sobre o fundo escuro.
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

  /** `yyyy-MM-dd` → `dd/MM/yyyy`, sem `Date` e portanto sem deslocamento de fuso. */
  formatDate(date: string): string {
    const [year, month, day] = date.split('-');
    return day && month && year ? `${day}/${month}/${year}` : date;
  }

  /** Eixo horizontal: só dia e mês, que é o que cabe em 90 pontos. */
  private formatAxisDate(date: string): string {
    const [, month, day] = date.split('-');
    return day && month ? `${day}/${month}` : date;
  }

  /** Mesma série, tokens novos. Nada é recarregado: só as cores mudaram. */
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
          // Losango contra círculo: a forma do marcador separa as séries na legenda e no
          // balão mesmo para quem não distingue as duas cores.
          pointStyle: 'rectRot',
          pointRadius: 2,
          pointHoverRadius: 5,
          borderWidth: 2,
          // Traço diferente para a segunda série: quem não distingue as duas cores ainda
          // separa as linhas.
          borderDash: [6, 4],
          tension: 0.25,
          fill: false,
        },
      ],
    };

    // O backend devolve a série completa, com zeros nos dias parados: "vazio" é a série
    // inteira zerada, e não a ausência de pontos.
    this.state = this.totalOpened === 0 && this.totalResolved === 0 ? 'empty' : null;
  }
}
