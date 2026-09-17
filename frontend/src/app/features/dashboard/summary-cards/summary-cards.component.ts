import { Component, OnInit } from '@angular/core';
import { Params } from '@angular/router';

import { ViewState } from '../../../shared/components/state-message/state-message.component';
import { DashboardSummary, ProjectSummary } from '../models/dashboard.model';
import { DashboardService } from '../services/dashboard.service';

/**
 * The card's shade, which decides the side hairline, the icon color and the number's. It is
 * reinforcement, never the only signal: the label and the icon already say what the number
 * is about.
 */
export type SummaryCardTone = 'critical' | 'high' | 'positive' | 'neutral';

/**
 * One card. `routerLink` + `queryParams` lead to the list already filtered: the number is
 * the start of an investigation, not an ornament.
 *
 * `linkHint` only exists on the cards whose filter the list cannot reproduce exactly — it
 * filters one status at a time, and "em aberto" on the backend is `OPEN + IN_PROGRESS`.
 * Saying so is more honest than sending the user to a list that counts differently from the
 * card, with no explanation.
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

/** Repeated on the two cards whose destination is an approximation of the number shown. */
const SINGLE_STATUS_HINT = 'A listagem filtra um status por vez: o link abre as Abertas.';

/**
 * The summary cards and the ten projects with the most findings. An independent async
 * region: the charts can fail without wiping out these numbers, and the other way around
 * holds too.
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
   * A freshly created company: the zeros are the correct answer, not a failure, so the cards
   * stay on the screen and get a sentence explaining what to do next.
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
   * The card's classes. The shade becomes visual emphasis only when there is something to
   * look at: a zeroed "críticas em aberto" is good news, and painting it red would teach the
   * analyst to ignore the color exactly where it needs to mean something.
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
