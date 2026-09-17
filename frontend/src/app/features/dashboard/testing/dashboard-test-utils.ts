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

/** Fixtures dos testes; nenhuma tela usa dados simulados. */
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

/** Resumo de empresa recém-criada: tudo zero, que é uma resposta legítima do backend. */
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
 * O backend devolve sempre as quatro severidades, na ordem do enum; `counts` segue essa
 * mesma ordem e por padrão traz zeros.
 */
export function makeSeverityDistribution(
  counts: readonly number[] = [0, 0, 0, 0],
): SeverityDistributionEntry[] {
  return SEVERITIES.map((severity, index) => ({ severity, count: counts[index] ?? 0 }));
}

/** Idem para os quatro status. */
export function makeStatusDistribution(
  counts: readonly number[] = [0, 0, 0, 0],
): StatusDistributionEntry[] {
  return VULNERABILITY_STATUSES.map((status, index) => ({ status, count: counts[index] ?? 0 }));
}

/**
 * Série contígua a partir de `from`, com um par `[abertas, resolvidas]` por dia. As datas
 * são montadas em UTC porque é assim que o backend fecha a janela.
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
