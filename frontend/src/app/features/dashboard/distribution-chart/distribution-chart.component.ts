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

/** Uma barra: rótulo, contagem, participação no total e a cor do tema. */
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
 * Definições fixas por tipo, na ordem de declaração do enum do backend — que é a ordem em
 * que as respostas chegam. As contagens são casadas por chave e não por posição: a tela
 * continua correta se a ordem da resposta mudar, e uma categoria ausente vira zero em vez
 * de sumir da legenda.
 */
// O fallback só entra em cena se o token não resolver — na prática, num teste que monte o
// componente sem a folha global. É sempre o valor do tema claro, porque uma constante não
// tem como saber o tema; o caminho real passa pelo token e troca junto com ele.
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
 * Opções do canvas, remontadas a cada tema porque o cromo também é tokenizado.
 *
 * `maintainAspectRatio: false` com altura fixa no CSS é o que impede o canvas de estourar a
 * coluna do grid em telas estreitas: sem isso o Chart.js mantém a proporção e cresce além
 * do contêiner.
 */
function buildOptions(): ChartConfiguration<'bar'>['options'] {
  const chrome = readChartChrome();

  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      // A legenda nativa do Chart.js descreveria o dataset, não as categorias; os rótulos
      // ficam no eixo e na legenda em HTML, que sobrevivem a qualquer daltonismo.
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
 * Distribuição por severidade ou por status. Um componente só porque as duas respostas têm
 * exatamente a mesma forma (categoria + contagem) e o desenho é o mesmo; o que muda é o
 * endpoint, os rótulos e as cores, todos tabelados acima.
 *
 * Região assíncrona independente: carrega, falha e é recarregada sozinha, sem afetar os
 * cards nem os outros gráficos.
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

  /** Última resposta aceita, guardada para repintar sem pedir os números de novo. */
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

  /** Resumo curto no canvas; os números completos ficam na tabela ao lado dele. */
  get chartLabel(): string {
    return `Gráfico de barras: ${this.title.toLowerCase()}, ${this.total} no total. Os valores estão na tabela seguinte.`;
  }

  ngOnInit(): void {
    this.load();

    // Trocar de tema troca os tokens, e o canvas não se repinta sozinho: as cores viram
    // pixels no momento do desenho. Aqui o gráfico é remontado com os tokens novos, sem
    // uma segunda ida ao servidor.
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

  /** Mesmos números, tokens novos. Nada é recarregado: só as cores mudaram. */
  private repaint(): void {
    this.chartOptions = buildOptions();
    if (this.counts) {
      this.apply(this.counts);
    }
  }

  /**
   * "Vazio" aqui é "tudo zero", e não "nenhuma linha": o backend sempre devolve as quatro
   * categorias. Nesse caso a tela diz isso em palavras em vez de desenhar um canvas em
   * branco que ninguém consegue interpretar.
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
          // Um array de cores, uma por barra: a categoria zerada mantém sua cor e sua
          // posição, então a legenda não se reordena entre dois carregamentos.
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
