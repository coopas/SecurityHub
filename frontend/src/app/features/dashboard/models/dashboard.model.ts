import { Severity, VulnerabilityStatus } from '../../vulnerabilities/models/vulnerability.model';

/** One row of `summary.topProjects`: at most ten, already sorted by the backend. */
export interface ProjectSummary {
  projectId: number;
  projectName: string;
  total: number;
  /** Open + In progress within the project. */
  open: number;
  overdue: number;
}

/**
 * `GET /api/v1/dashboard/summary`. `openVulnerabilities` is not the count of the `OPEN`
 * status: it is the "still actionable" bucket, `OPEN + IN_PROGRESS`, as documented on
 * `DashboardSummaryResponse`. The cards repeat that distinction in text so the number is
 * not read as the list's status filter.
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

/** Item of `GET /dashboard/severity-distribution`: plain array, always with the 4 severities. */
export interface SeverityDistributionEntry {
  severity: Severity;
  count: number;
}

/** Item of `GET /dashboard/status-distribution`: plain array, always with the 4 statuses. */
export interface StatusDistributionEntry {
  status: VulnerabilityStatus;
  count: number;
}

/** One day of the series, in UTC. Days with no movement come back with zeros. */
export interface TrendPoint {
  /** `yyyy-MM-dd`; the backend serializes `LocalDate` as ISO text. */
  date: string;
  opened: number;
  resolved: number;
}

/**
 * `GET /dashboard/trend?days=30`. `days`, `from` and `to` echo the window the server actually
 * used — the request is silently clamped to [1, 90] — so the screen always labels the chart
 * with what came back in the response, never with what was asked for.
 */
export interface Trend {
  days: number;
  from: string;
  to: string;
  points: TrendPoint[];
}

export const DEFAULT_TREND_DAYS = 30;

/** Options of the window selector; all of them inside the range the backend accepts. */
export const TREND_DAYS_OPTIONS: readonly number[] = [7, 30, 90];

/** Number of items in the "Itens recentes" panel. */
export const RECENT_VULNERABILITIES_SIZE = 5;

/** Sort order of the recent items panel; `createdAt` is sortable on the backend. */
export const RECENT_VULNERABILITIES_SORT = 'createdAt,desc';
