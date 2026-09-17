import { Component, Input, OnInit } from '@angular/core';
import { ChartConfiguration } from 'chart.js';
import { Observable, map } from 'rxjs';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import {
  SEVERITIES,
  SEVERITY_LABELS,
  VULNERABILITY_STATUSES,
  VULNERABILITY_STATUS_LABELS,
} from '../../vulnerabilities/models/vulnerability.model';
import { DashboardService } from '../services/dashboard.service';
import { readThemeColor } from '../utils/theme-color.util';

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
const DEFINITIONS: Readonly<Record<DistributionKind, readonly CategoryDefinition[]>> = {
  severity: SEVERITIES.map((severity) => ({
    key: severity,
    label: SEVERITY_LABELS[severity],
    token: `--sh-${severity.toLowerCase()}`,
    fallback: '#55596b',
  })),
  status: VULNERABILITY_STATUSES.map((status) => ({
    key: status,
    label: VULNERABILITY_STATUS_LABELS[status],
    token: `--sh-${status.toLowerCase().replace('_', '-')}`,
    fallback: '#55596b',
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
export class DistributionChartComponent implements OnInit {
  @Input({ required: true }) kind: DistributionKind = 'severity';

  state: ViewState | null = 'loading';
  categories: DistributionCategory[] = [];
  total = 0;

  chartData: ChartConfiguration<'bar'>['data'] = { labels: [], datasets: [] };

  /**
   * `maintainAspectRatio: false` com altura fixa no CSS é o que impede o canvas de
   * estourar a coluna do grid em telas estreitas: sem isso o Chart.js mantém a proporção
   * e cresce além do contêiner.
   */
  readonly chartOptions: ChartConfiguration<'bar'>['options'] = {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      // A legenda nativa do Chart.js descreveria o dataset, não as categorias; os rótulos
      // ficam no eixo e na legenda em HTML, que sobrevivem a qualquer daltonismo.
      legend: { display: false },
    },
    scales: {
      x: { grid: { display: false } },
      y: { beginAtZero: true, ticks: { precision: 0 } },
    },
  };

  constructor(private readonly dashboardService: DashboardService) {}

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
  }

  load(): void {
    this.state = 'loading';
    this.fetch().subscribe({
      next: (counts) => {
        this.apply(counts);
      },
      error: () => {
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

  /**
   * "Vazio" aqui é "tudo zero", e não "nenhuma linha": o backend sempre devolve as quatro
   * categorias. Nesse caso a tela diz isso em palavras em vez de desenhar um canvas em
   * branco que ninguém consegue interpretar.
   */
  private apply(counts: Map<string, number>): void {
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
          maxBarThickness: 96,
        },
      ],
    };

    this.state = this.total === 0 ? 'empty' : null;
  }
}
