import { Component, OnInit } from '@angular/core';
import { ChartConfiguration } from 'chart.js';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DEFAULT_TREND_DAYS, TREND_DAYS_OPTIONS, Trend } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';
import { readThemeColor } from '../utils/theme-color.util';

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
export class TrendChartComponent implements OnInit {
  readonly daysOptions = TREND_DAYS_OPTIONS;

  /** Janela pedida; a efetiva é sempre a que voltou na resposta. */
  selectedDays = DEFAULT_TREND_DAYS;

  state: ViewState | null = 'loading';
  trend: Trend | null = null;
  rows: TrendRow[] = [];
  totalOpened = 0;
  totalResolved = 0;

  chartData: ChartConfiguration<'line'>['data'] = { labels: [], datasets: [] };

  readonly chartOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      // Legenda nativa com texto ao lado de cada cor: as séries nunca são distinguidas
      // apenas pela cor. O traçado também difere, contínuo para abertas e tracejado para
      // resolvidas.
      legend: { display: true, position: 'top', labels: { boxHeight: 8, usePointStyle: true } },
    },
    scales: {
      x: { grid: { display: false }, ticks: { maxRotation: 0, autoSkipPadding: 16 } },
      y: { beginAtZero: true, ticks: { precision: 0 } },
    },
  };

  constructor(private readonly dashboardService: DashboardService) {}

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

    const openedColor = readThemeColor('--sh-open', '#b3261e');
    const resolvedColor = readThemeColor('--sh-resolved', '#1f5e3a');

    this.chartData = {
      labels: points.map((point) => this.formatAxisDate(point.date)),
      datasets: [
        {
          label: OPENED_LABEL,
          data: points.map((point) => point.opened),
          borderColor: openedColor,
          backgroundColor: openedColor,
          pointBackgroundColor: openedColor,
          pointRadius: 2,
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
          pointRadius: 2,
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
