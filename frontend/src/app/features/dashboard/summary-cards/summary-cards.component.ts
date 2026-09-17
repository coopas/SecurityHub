import { Component, OnInit } from '@angular/core';
import { Params } from '@angular/router';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DashboardSummary, ProjectSummary } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';

/**
 * Tom do card, que decide o filete lateral, a cor do ícone e a do número. É reforço, nunca
 * o único sinal: o rótulo e o ícone já dizem do que o número trata.
 */
export type SummaryCardTone = 'critical' | 'high' | 'positive' | 'neutral';

/**
 * Um card. `routerLink` + `queryParams` levam à listagem já filtrada: o número é o começo
 * de uma investigação, não um enfeite.
 *
 * `linkHint` só existe nos cards cujo filtro a listagem não consegue reproduzir exatamente
 * — ela filtra um status por vez, e "em aberto" no backend é `OPEN + IN_PROGRESS`. Dizer
 * isso é mais honesto do que mandar o usuário para uma lista que conta diferente do card
 * sem explicação.
 */
export interface SummaryCard {
  key: string;
  label: string;
  value: number;
  icon: string;
  hint: string;
  tone: SummaryCardTone;
  routerLink: string;
  queryParams: Params;
  linkHint?: string;
}

/** Repetido nos dois cards cujo destino é uma aproximação do número exibido. */
const SINGLE_STATUS_HINT = 'A listagem filtra um status por vez: o link abre as Abertas.';

/**
 * Cards do resumo e os dez projetos com mais achados. Região assíncrona independente: os
 * gráficos podem falhar sem apagar estes números, e o contrário também vale.
 */
@Component({
  selector: 'app-summary-cards',
  templateUrl: './summary-cards.component.html',
  styleUrls: ['../dashboard.scss'],
})
export class SummaryCardsComponent implements OnInit {
  state: ViewState | null = 'loading';
  summary: DashboardSummary | null = null;
  cards: SummaryCard[] = [];

  constructor(private readonly dashboardService: DashboardService) {}

  get topProjects(): ProjectSummary[] {
    return this.summary?.topProjects ?? [];
  }

  /**
   * Empresa recém-criada: os zeros são a resposta correta, não uma falha, então os cards
   * continuam na tela e ganham uma frase que explica o que fazer em seguida.
   */
  get isEmptyCompany(): boolean {
    return (
      !!this.summary &&
      this.summary.totalVulnerabilities === 0 &&
      this.summary.totalProjects === 0 &&
      this.summary.totalAssets === 0
    );
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'loading';
    this.dashboardService.summary().subscribe({
      next: (summary) => {
        this.summary = summary;
        this.cards = this.buildCards(summary);
        this.state = null;
      },
      error: () => {
        this.summary = null;
        this.cards = [];
        this.state = 'error';
      },
    });
  }

  trackByKey(_index: number, card: SummaryCard): string {
    return card.key;
  }

  /**
   * Classes do card. O tom vira ênfase visual só quando há o que olhar: um "críticas em
   * aberto" zerado é boa notícia, e pintá-lo de vermelho ensinaria o analista a ignorar a
   * cor justamente onde ela precisa significar alguma coisa.
   */
  cardClasses(card: SummaryCard): string[] {
    const classes = [`dashboard-card--${card.tone}`];
    if (card.value === 0) {
      classes.push('dashboard-card--quiet');
    }
    return classes;
  }

  trackByProjectId(_index: number, project: ProjectSummary): number {
    return project.projectId;
  }

  private buildCards(summary: DashboardSummary): SummaryCard[] {
    return [
      {
        key: 'total',
        label: 'Vulnerabilidades',
        value: summary.totalVulnerabilities,
        icon: 'bug_report',
        tone: 'neutral',
        hint: 'Todos os achados da empresa.',
        routerLink: '/vulnerabilities',
        queryParams: {},
      },
      {
        key: 'open',
        label: 'Em aberto',
        value: summary.openVulnerabilities,
        icon: 'error_outline',
        tone: 'high',
        hint: 'Status Aberta e Em andamento, as que ainda exigem ação.',
        routerLink: '/vulnerabilities',
        queryParams: { status: 'OPEN' },
        linkHint: SINGLE_STATUS_HINT,
      },
      {
        key: 'criticalOpen',
        label: 'Críticas em aberto',
        value: summary.criticalOpenVulnerabilities,
        icon: 'priority_high',
        tone: 'critical',
        hint: 'Severidade Crítica ainda em aberto ou em andamento.',
        routerLink: '/vulnerabilities',
        queryParams: { severity: 'CRITICAL', status: 'OPEN' },
        linkHint: SINGLE_STATUS_HINT,
      },
      {
        key: 'overdue',
        label: 'Atrasadas',
        value: summary.overdueVulnerabilities,
        icon: 'schedule',
        tone: 'high',
        hint: 'Prazo vencido e ainda sem resolução.',
        routerLink: '/vulnerabilities',
        queryParams: { overdue: 'true' },
      },
      {
        key: 'resolved',
        label: 'Resolvidas',
        value: summary.resolvedVulnerabilities,
        icon: 'check_circle',
        tone: 'positive',
        hint: 'Status Resolvida.',
        routerLink: '/vulnerabilities',
        queryParams: { status: 'RESOLVED' },
      },
      {
        key: 'projects',
        label: 'Projetos',
        value: summary.totalProjects,
        icon: 'folder_open',
        tone: 'neutral',
        hint: 'Projetos cadastrados na empresa.',
        routerLink: '/projects',
        queryParams: {},
      },
      {
        key: 'assets',
        label: 'Ativos',
        value: summary.totalAssets,
        icon: 'dns',
        tone: 'neutral',
        hint: 'Ativos monitorados na empresa.',
        routerLink: '/assets',
        queryParams: {},
      },
    ];
  }
}
