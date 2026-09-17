import { Severity, VulnerabilityStatus } from '../../vulnerabilities/models/vulnerability.model';

/** Uma linha de `summary.topProjects`: no máximo dez, já ordenadas pelo backend. */
export interface ProjectSummary {
  projectId: number;
  projectName: string;
  total: number;
  /** Aberta + Em andamento dentro do projeto. */
  open: number;
  overdue: number;
}

/**
 * `GET /api/v1/dashboard/summary`. `openVulnerabilities` não é a contagem do status
 * `OPEN`: é o balde "ainda acionável", `OPEN + IN_PROGRESS`, como documentado no
 * `DashboardSummaryResponse`. Os cards repetem essa distinção em texto para que o número
 * não seja lido como o filtro de status da listagem.
 */
export interface DashboardSummary {
  totalVulnerabilities: number;
  openVulnerabilities: number;
  criticalOpenVulnerabilities: number;
  overdueVulnerabilities: number;
  resolvedVulnerabilities: number;
  totalProjects: number;
  totalAssets: number;
  topProjects: ProjectSummary[];
}

/** Item de `GET /dashboard/severity-distribution`: array puro, sempre com as 4 severidades. */
export interface SeverityDistributionEntry {
  severity: Severity;
  count: number;
}

/** Item de `GET /dashboard/status-distribution`: array puro, sempre com os 4 status. */
export interface StatusDistributionEntry {
  status: VulnerabilityStatus;
  count: number;
}

/** Um dia da série, em UTC. Dias sem movimento vêm com zeros. */
export interface TrendPoint {
  /** `yyyy-MM-dd`; o backend serializa `LocalDate` como texto ISO. */
  date: string;
  opened: number;
  resolved: number;
}

/**
 * `GET /dashboard/trend?days=30`. `days`, `from` e `to` ecoam a janela que o servidor
 * realmente usou — o pedido é limitado silenciosamente a [1, 90] —, então a tela sempre
 * rotula o gráfico com o que veio na resposta, nunca com o que foi pedido.
 */
export interface Trend {
  days: number;
  from: string;
  to: string;
  points: TrendPoint[];
}

export const DEFAULT_TREND_DAYS = 30;

/** Opções do seletor de janela; todas dentro do intervalo aceito pelo backend. */
export const TREND_DAYS_OPTIONS: readonly number[] = [7, 30, 90];

/** Quantidade de itens do painel "Itens recentes". */
export const RECENT_VULNERABILITIES_SIZE = 5;

/** Ordenação do painel de itens recentes; `createdAt` é ordenável no backend. */
export const RECENT_VULNERABILITIES_SORT = 'createdAt,desc';
