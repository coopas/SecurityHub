import {
  SEVERITIES,
  VULNERABILITY_STATUSES,
} from '../../vulnerabilities/models/vulnerability.model';
import {
  DashboardSummary,
  ProjectSummary,
  SeverityDistributionEntry,
  StatusDistributionEntry,
  Trend,
} from '../models/dashboard.model';

/** Test fixtures; no screen uses simulated data. */
export function makeProjectSummary(overrides: Partial<ProjectSummary> = {}): ProjectSummary {
  return {
    projectId: 3,
    projectName: 'Portal do cliente',
    total: 9,
    open: 5,
    overdue: 2,
    ...overrides,
  };
}

export function makeDashboardSummary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    totalVulnerabilities: 42,
    openVulnerabilities: 17,
    criticalOpenVulnerabilities: 4,
    overdueVulnerabilities: 6,
    resolvedVulnerabilities: 21,
    totalProjects: 3,
    totalAssets: 11,
    topProjects: [makeProjectSummary()],
    ...overrides,
  };
}

/** Summary of a freshly created company: all zeros, a legitimate response from the backend. */
export function makeEmptyDashboardSummary(): DashboardSummary {
  return {
    totalVulnerabilities: 0,
    openVulnerabilities: 0,
    criticalOpenVulnerabilities: 0,
    overdueVulnerabilities: 0,
    resolvedVulnerabilities: 0,
    totalProjects: 0,
    totalAssets: 0,
    topProjects: [],
  };
}

/**
 * The backend always returns the four severities, in enum order; `counts` follows that same
 * order and brings zeros by default.
 */
export function makeSeverityDistribution(
  counts: readonly number[] = [0, 0, 0, 0],
): SeverityDistributionEntry[] {
  return SEVERITIES.map((severity, index) => ({ severity, count: counts[index] ?? 0 }));
}

/** Same for the four statuses. */
export function makeStatusDistribution(
  counts: readonly number[] = [0, 0, 0, 0],
): StatusDistributionEntry[] {
  return VULNERABILITY_STATUSES.map((status, index) => ({ status, count: counts[index] ?? 0 }));
}

/**
 * A contiguous series starting at `from`, with one `[opened, resolved]` pair per day. The
 * dates are built in UTC because that is how the backend closes the window.
 */
export function makeTrend(pairs: readonly (readonly number[])[], from = '2026-01-01'): Trend {
  const start = new Date(`${from}T00:00:00Z`);
  const points = pairs.map((pair, index) => {
    const day = new Date(start.getTime() + index * 86_400_000);
    return {
      date: day.toISOString().slice(0, 10),
      opened: pair[0] ?? 0,
      resolved: pair[1] ?? 0,
    };
  });

  return {
    days: points.length,
    from: points[0]?.date ?? from,
    to: points[points.length - 1]?.date ?? from,
    points,
  };
}
