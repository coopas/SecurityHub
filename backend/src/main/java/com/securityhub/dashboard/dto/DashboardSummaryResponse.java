package com.securityhub.dashboard.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Card set of {@code GET /api/v1/dashboard/summary}.
 *
 * There is deliberately no per-status breakdown here: {@code /status-distribution} owns
 * that and two homes for one number is how they drift. {@code openVulnerabilities} is the
 * single exception and is not a status count — it is the "still actionable" bucket,
 * {@code OPEN + IN_PROGRESS}, which is what the card and the {@code overdue} rule mean by
 * open.
 */
@Getter
@AllArgsConstructor
public class DashboardSummaryResponse {

    private final long totalVulnerabilities;

    /** OPEN + IN_PROGRESS, the two statuses {@code VulnerabilityStatus.isActive} covers. */
    private final long openVulnerabilities;

    private final long criticalOpenVulnerabilities;

    /** Same predicate as {@code GET /vulnerabilities?overdue=true} (docs/data-model.md). */
    private final long overdueVulnerabilities;

    private final long resolvedVulnerabilities;

    private final long totalProjects;

    private final long totalAssets;

    /** At most ten, ordered by total desc then name asc; projects with no finding are absent. */
    private final List<ProjectSummaryResponse> topProjects;
}
