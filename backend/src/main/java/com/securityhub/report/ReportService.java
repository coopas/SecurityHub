package com.securityhub.report;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.dashboard.DashboardService;
import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.NotFoundException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The executive report, assembled from aggregations that already exist.
 *
 * <p>{@link DashboardService} is called unchanged for six of the eight sections. Recomputing any
 * of those numbers here would create the second source of truth the dashboard DTOs warn about,
 * and a report that disagreed with the screen it summarises is the most visible possible bug —
 * it is read by the people least able to tell which of the two is wrong.
 *
 * <p>There is deliberately no date range and no filter. Every aggregation reused is "as of now",
 * so a range would mean either reimplementing all seven of them over a window — exactly the
 * second implementation above — or applying it to one section out of eight, which is worse than
 * not offering it at all. The document says on its first page that it is a snapshot.
 *
 * <p>Generation is synchronous: six indexed aggregates and about sixty rows of layout. An
 * asynchronous version would need a job store, a status endpoint and a second authorization check
 * at retrieval time — a subsystem for a sub-second operation (docs/adr/0008).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Matches {@code VulnerabilityService.ENTITY_TYPE}'s role: the label the trail is read by. */
    static final String ENTITY_TYPE = "Report";

    /**
     * A listing on paper is a prompt to act, not a backlog. Ten rows is what fits beside the
     * seven other sections; the drill-down is {@code GET /vulnerabilities}, which is paged.
     */
    static final int MAX_LIST_ROWS = 10;

    /** The window of §10's trend card, so the line on paper and the chart agree. */
    static final int TREND_DAYS = 30;

    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final DashboardService dashboardService;
    private final ReportRepository reportRepository;
    private final CompanyRepository companyRepository;
    private final ExecutiveReportPdfWriter pdfWriter;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ExecutiveReport generateExecutivePdf(AuthenticatedUser current) {
        Long companyId = current.getCompanyId();
        // One instant for the whole document: the overdue list and the generated-at stamp in the
        // header must describe the same moment, whatever the layout costs in between.
        Instant now = Instant.now();

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> NotFoundException.of("Empresa", companyId));

        // The dashboard aggregations pin an instant of their own, a few microseconds earlier.
        // That is accepted rather than worked around: passing this one in would mean changing
        // DashboardService, and reusing it unchanged is the entire point of this class.
        DashboardSummaryResponse summary = dashboardService.summary(current);
        List<SeverityDistributionResponse> severities = dashboardService.severityDistribution(current);
        List<StatusDistributionResponse> statuses = dashboardService.statusDistribution(current);
        TrendResponse trend = dashboardService.trend(current, TREND_DAYS);

        byte[] content = pdfWriter.write(company, current, now, summary, severities, statuses, trend,
                reportRepository.criticalOpen(companyId, MAX_LIST_ROWS),
                reportRepository.overdue(companyId, now, MAX_LIST_ROWS));

        recordExport(current, content.length);
        log.info("Relatório executivo gerado para a empresa {} ({} bytes)", companyId, content.length);
        return new ExecutiveReport(content, fileName(company.getSlug(), now));
    }

    /**
     * The slug and not the name: {@code SlugGenerator} already guarantees lowercase ASCII with no
     * spaces, which is what keeps the name usable across every filesystem and every
     * {@code Content-Disposition} parser without a second sanitiser here.
     */
    static String fileName(String slug, Instant now) {
        return "relatorio-executivo-" + slug + "-" + FILE_DATE.format(now) + ".pdf";
    }

    /**
     * {@code recordIndependently} is required here, not preferred. The surrounding transaction is
     * {@code readOnly = true}, which puts the PostgreSQL connection itself in read-only mode, and
     * an INSERT on it fails with "cannot execute INSERT in a read-only transaction". The audit row
     * needs its own transaction, which is safe because it references nothing this one created.
     * {@code VulnerabilityExportService} carries the same note for the same reason.
     *
     * <p>Only the shape and the size of the answer are recorded. The report is a copy of the
     * tenant's backlog; putting its content in the trail would defeat the point of keeping the
     * trail readable and would duplicate the data somewhere it is not expected to be.
     */
    private void recordExport(AuthenticatedUser current, int sizeBytes) {
        Map<String, Object> values = AuditEntry.values();
        values.put("report", "executive");
        values.put("format", "pdf");
        values.put("sizeBytes", sizeBytes);
        auditService.recordIndependently(AuditEntry.changed(current, AuditAction.EXPORT,
                ENTITY_TYPE, null, null, values));
    }
}
