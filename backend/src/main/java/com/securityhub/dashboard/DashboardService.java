package com.securityhub.dashboard;

import com.securityhub.asset.AssetRepository;
import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.ProjectSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendPointResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.project.ProjectRepository;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregates behind {@code /api/v1/dashboard}.
 *
 * There is intentionally no {@code @PreAuthorize} on this class. docs/permissions.md grants
 * "ver dashboard" to ADMIN, ANALYST, DEVELOPER and VIEWER — every role there is — so the
 * only requirement is authentication, which {@code SecurityConfig} already enforces with
 * {@code anyRequest().authenticated()}. An annotation listing all four roles would be a
 * no-op that adds nothing today and can only go stale the day a fifth role appears.
 *
 * Every query is scoped by {@code current.getCompanyId()}; {@code companyId} is never a
 * request parameter. Mapping happens inside the transactional methods because
 * {@code open-in-view} is disabled.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Mirrors {@code PageableSupport.MAX_PAGE_SIZE}: a clamp, never a 400. */
    static final int MIN_TREND_DAYS = 1;
    static final int MAX_TREND_DAYS = 90;

    /** The breakdown is a card, not a listing: a long tail of projects would not fit it. */
    static final int TOP_PROJECTS_LIMIT = 10;

    private final DashboardRepository dashboardRepository;
    private final ProjectRepository projectRepository;
    private final AssetRepository assetRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(AuthenticatedUser current) {
        Long companyId = current.getCompanyId();
        // One instant for the whole card set: two calls to Instant.now() could put the
        // overdue count and the per-project overdue counts on different sides of a due date.
        Instant now = Instant.now();

        Object[] totals = firstRow(dashboardRepository.summaryCounts(companyId, now));
        List<ProjectSummaryResponse> topProjects = new ArrayList<>(TOP_PROJECTS_LIMIT);
        for (Object[] row : dashboardRepository.topProjects(companyId, now, TOP_PROJECTS_LIMIT)) {
            topProjects.add(new ProjectSummaryResponse(
                    longAt(row, 0), (String) row[1], longAt(row, 2), longAt(row, 3), longAt(row, 4)));
        }

        return new DashboardSummaryResponse(
                longAt(totals, 0),
                longAt(totals, 1),
                longAt(totals, 2),
                longAt(totals, 3),
                longAt(totals, 4),
                projectRepository.countByCompanyId(companyId),
                assetRepository.countByCompanyId(companyId),
                topProjects);
    }

    /**
     * Zero-filled in Java by walking {@code Severity.values()}. The database only returns the
     * severities that actually occur, and a chart whose legend gains and loses entries — and
     * reshuffles its colours — between two reloads is worse than one with visible zeros.
     * Iterating the enum also pins the order to the declaration order.
     */
    @Transactional(readOnly = true)
    public List<SeverityDistributionResponse> severityDistribution(AuthenticatedUser current) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Object[] row : dashboardRepository.countBySeverity(current.getCompanyId())) {
            counts.put((Severity) row[0], longAt(row, 1));
        }
        List<SeverityDistributionResponse> result = new ArrayList<>(Severity.values().length);
        for (Severity severity : Severity.values()) {
            result.add(new SeverityDistributionResponse(severity, counts.getOrDefault(severity, 0L)));
        }
        return result;
    }

    /** Twin of {@link #severityDistribution}; deliberately not folded into a generic helper. */
    @Transactional(readOnly = true)
    public List<StatusDistributionResponse> statusDistribution(AuthenticatedUser current) {
        Map<VulnerabilityStatus, Long> counts = new EnumMap<>(VulnerabilityStatus.class);
        for (Object[] row : dashboardRepository.countByStatus(current.getCompanyId())) {
            counts.put((VulnerabilityStatus) row[0], longAt(row, 1));
        }
        List<StatusDistributionResponse> result = new ArrayList<>(VulnerabilityStatus.values().length);
        for (VulnerabilityStatus status : VulnerabilityStatus.values()) {
            result.add(new StatusDistributionResponse(status, counts.getOrDefault(status, 0L)));
        }
        return result;
    }

    /**
     * Two series bucketed by calendar day in UTC: {@code opened} from {@code discoveredAt}
     * and {@code resolved} from {@code resolvedAt}.
     *
     * Neither series uses {@code createdAt} on purpose. In a seeded or imported dataset every
     * row shares one {@code createdAt}, so a trend built on it renders as a single spike that
     * says nothing about the backlog.
     *
     * The window is {@code [today - (days - 1), today]} in UTC, both inclusive, so
     * {@code days=30} yields exactly 30 points.
     */
    @Transactional(readOnly = true)
    public TrendResponse trend(AuthenticatedUser current, int requestedDays) {
        int days = clampDays(requestedDays);
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(days - 1L);
        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Exclusive upper bound at the start of tomorrow: a half-open range stays sargable
        // and never has to reason about the last microsecond of the day.
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<TrendPointResponse> points = new ArrayList<>(days);
        for (Object[] row : dashboardRepository.trend(current.getCompanyId(), from.toString(), to.toString(),
                fromInstant, toInstant)) {
            // Parsed from text, never from java.sql.Date, which would go through the JVM
            // default zone and can hand back the previous day.
            points.add(new TrendPointResponse(LocalDate.parse((String) row[0]), longAt(row, 1), longAt(row, 2)));
        }
        return new TrendResponse(days, from, to, points);
    }

    /**
     * Silent clamp instead of a 400. The caller is a chart control, and a slider that can
     * raise an error dialog is a broken control; the effective value is echoed in the
     * response so the clamp is visible rather than hidden.
     */
    static int clampDays(int days) {
        return Math.min(Math.max(days, MIN_TREND_DAYS), MAX_TREND_DAYS);
    }

    /**
     * With {@code nativeQuery = true} Hibernate 5 returns {@code count(*)} as
     * {@link java.math.BigInteger} and {@code bigserial} ids as {@code BigInteger} too, while
     * the JPQL aggregates come back as {@code Long}. Reading every column through
     * {@link Number} is what keeps both shapes out of a {@code ClassCastException}.
     */
    private static long longAt(Object[] row, int index) {
        Object value = row[index];
        return value == null ? 0L : ((Number) value).longValue();
    }

    /**
     * The aggregate query always produces exactly one row, even for an empty company, but an
     * empty list is handled rather than trusted: a summary of zeros beats a 500.
     */
    private static Object[] firstRow(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[] {0L, 0L, 0L, 0L, 0L} : rows.get(0);
    }
}
