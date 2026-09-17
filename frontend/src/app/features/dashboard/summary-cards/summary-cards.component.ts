import { Component, OnInit } from '@angular/core';
import { Params } from '@angular/router';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DashboardSummary, ProjectSummary } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';

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
        hint: 'Todos os achados da empresa.',
        routerLink: '/vulnerabilities',
        queryParams: {},
      },
      {
        key: 'open',
        label: 'Em aberto',
        value: summary.openVulnerabilities,
        icon: 'error_outline',
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
        hint: 'Prazo vencido e ainda sem resolução.',
        routerLink: '/vulnerabilities',
        queryParams: { overdue: 'true' },
      },
      {
        key: 'resolved',
        label: 'Resolvidas',
        value: summary.resolvedVulnerabilities,
        icon: 'check_circle',
        hint: 'Status Resolvida.',
        routerLink: '/vulnerabilities',
        queryParams: { status: 'RESOLVED' },
      },
      {
        key: 'projects',
        label: 'Projetos',
        value: summary.totalProjects,
        icon: 'folder_open',
        hint: 'Projetos cadastrados na empresa.',
        routerLink: '/projects',
        queryParams: {},
      },
      {
        key: 'assets',
        label: 'Ativos',
        value: summary.totalAssets,
        icon: 'dns',
        hint: 'Ativos monitorados na empresa.',
        routerLink: '/assets',
        queryParams: {},
      },
    ];
  }
}
