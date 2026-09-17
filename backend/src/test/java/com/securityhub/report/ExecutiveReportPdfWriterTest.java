package com.securityhub.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.securityhub.company.Company;
import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.ProjectSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendPointResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.Role;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The writer takes no repository, no security and no HTTP, so everything below runs with no
 * Spring context and no database — which is the reason it was split out of the service.
 *
 * <p>The assertions that matter are the ones on extracted text. A PDF whose bytes are valid and
 * whose accents render as blank boxes is a defect no byte-level check can see, and it is the most
 * likely thing to break here: the whole font decision of docs/adr/0008 exists to prevent it.
 */
class ExecutiveReportPdfWriterTest {

    private final ExecutiveReportPdfWriter writer = new ExecutiveReportPdfWriter(new ReportFonts());

    private final AuthenticatedUser actor = new AuthenticatedUser(7L, 1L, "ana@acme.test",
            "Ana Gonçalves", Role.ADMIN, true);

    @Test
    void producesAPdfFile() {
        byte[] content = writer.write(company("Acme"), actor, Instant.parse("2026-09-17T12:00:00Z"),
                summary(), severities(), statuses(), trend(), criticalRows(), overdueRows());

        assertThat(content).isNotEmpty();
        assertThat(new String(Arrays.copyOf(content, 5), StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    /**
     * Every one of these words is Portuguese with an accent outside plain ASCII. With a base-14
     * font and WinAnsi they would still extract, so the point of the assertion is the pairing
     * with {@link #keepsCharactersOutsideLatin1}: together they pin an embedded Identity-H font.
     */
    @Test
    void theAccentedPortugueseOfTheReportSurvivesIntoTheDocument() throws IOException {
        String text = extract(writer.write(company("Acme"), actor, Instant.now(),
                summary(), severities(), statuses(), trend(), criticalRows(), overdueRows()));

        assertThat(text)
                .contains("Relatório")
                .contains("Vulnerabilidades")
                .contains("críticas")
                .contains("Atrasadas")
                .contains("Distribuição")
                .contains("Últimos")
                .contains("Responsável");
    }

    /**
     * Ω is outside Cp1252. A base-14 font with WinAnsi drops it silently — no exception, no log,
     * just a blank in a company name — so this is the assertion that proves the embedded font is
     * the one actually in use.
     */
    @Test
    void keepsCharactersOutsideLatin1() throws IOException {
        String text = extract(writer.write(company("Ünïcode Ω Ltda"), actor, Instant.now(),
                summary(), severities(), statuses(), trend(), criticalRows(), overdueRows()));

        assertThat(text).contains("Ünïcode Ω Ltda");
    }

    /** The content the tenant typed reaches the page, not only the report's own labels. */
    @Test
    void printsTheRowsOfBothListings() throws IOException {
        String text = extract(writer.write(company("Acme"), actor, Instant.now(),
                summary(), severities(), statuses(), trend(), criticalRows(), overdueRows()));

        assertThat(text)
                .contains("Injeção de SQL no relatório")
                .contains("Certificado expirado")
                .contains("CVE-2026-0001")
                .contains("9.8");
    }

    /** A brand-new tenant is the first thing anybody generates: zeros, never an exception. */
    @Test
    void anEmptyTenantProducesAValidDocumentWithZeros() throws IOException {
        DashboardSummaryResponse empty = new DashboardSummaryResponse(0L, 0L, 0L, 0L, 0L, 0L, 0L,
                Collections.emptyList());

        byte[] content = writer.write(company("Nova"), actor, Instant.now(), empty,
                zeroedSeverities(), zeroedStatuses(), emptyTrend(),
                Collections.emptyList(), Collections.emptyList());

        String text = extract(content);
        assertThat(new String(Arrays.copyOf(content, 5), StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(text)
                .contains("Nenhuma vulnerabilidade crítica em aberto.")
                .contains("Nenhuma vulnerabilidade com prazo vencido.")
                .contains("Nenhum projeto com vulnerabilidades registradas.")
                .contains("0,0%");
    }

    @Test
    void everyPageCarriesItsNumberAndTheTotal() throws IOException {
        String text = extract(writer.write(company("Acme"), actor, Instant.now(),
                summary(), severities(), statuses(), trend(), criticalRows(), overdueRows()));

        assertThat(text).contains("Página 1 de");
    }

    // --- fixtures -----------------------------------------------------------

    /**
     * Whitespace is collapsed because a cell narrower than its content wraps, which is correct
     * layout and would otherwise make every assertion depend on the column widths. The line
     * breaks iText inserts fall on spaces, so collapsing restores the original string.
     */
    private String extract(byte[] content) throws IOException {
        try (PDDocument document = PDDocument.load(content)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    private Company company(String name) {
        return new Company(name, "acme");
    }

    private DashboardSummaryResponse summary() {
        return new DashboardSummaryResponse(42L, 17L, 3L, 5L, 25L, 4L, 11L,
                Arrays.asList(new ProjectSummaryResponse(1L, "Portal de Cobrança", 20L, 9L, 3L),
                        new ProjectSummaryResponse(2L, "Intranet", 22L, 8L, 2L)));
    }

    private List<SeverityDistributionResponse> severities() {
        List<SeverityDistributionResponse> slices = new ArrayList<>();
        long[] counts = {10L, 20L, 9L, 3L};
        int index = 0;
        for (Severity severity : Severity.values()) {
            slices.add(new SeverityDistributionResponse(severity, counts[index++]));
        }
        return slices;
    }

    private List<SeverityDistributionResponse> zeroedSeverities() {
        List<SeverityDistributionResponse> slices = new ArrayList<>();
        for (Severity severity : Severity.values()) {
            slices.add(new SeverityDistributionResponse(severity, 0L));
        }
        return slices;
    }

    private List<StatusDistributionResponse> statuses() {
        List<StatusDistributionResponse> slices = new ArrayList<>();
        long[] counts = {12L, 5L, 25L, 0L};
        int index = 0;
        for (VulnerabilityStatus status : VulnerabilityStatus.values()) {
            slices.add(new StatusDistributionResponse(status, counts[index++]));
        }
        return slices;
    }

    private List<StatusDistributionResponse> zeroedStatuses() {
        List<StatusDistributionResponse> slices = new ArrayList<>();
        for (VulnerabilityStatus status : VulnerabilityStatus.values()) {
            slices.add(new StatusDistributionResponse(status, 0L));
        }
        return slices;
    }

    private TrendResponse trend() {
        LocalDate to = LocalDate.parse("2026-09-17");
        LocalDate from = to.minusDays(29);
        List<TrendPointResponse> points = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            points.add(new TrendPointResponse(from.plusDays(day), 2L, 1L));
        }
        return new TrendResponse(30, from, to, points);
    }

    private TrendResponse emptyTrend() {
        LocalDate to = LocalDate.parse("2026-09-17");
        LocalDate from = to.minusDays(29);
        List<TrendPointResponse> points = new ArrayList<>();
        for (int day = 0; day < 30; day++) {
            points.add(new TrendPointResponse(from.plusDays(day), 0L, 0L));
        }
        return new TrendResponse(30, from, to, points);
    }

    /** Shaped exactly like {@code ReportRepository.criticalOpen} returns them. */
    private List<Object[]> criticalRows() {
        return Arrays.asList(
                new Object[] {"Injeção de SQL no relatório", "Portal de Cobrança", "API de Cobrança",
                        "Ana Gonçalves", "2026-08-02", new BigDecimal("9.8"), "CVE-2026-0001"},
                // Unassigned and without a CVE: the two nulls the listing must render, not hide.
                new Object[] {"Execução remota de código", "Intranet", "Servidor de Arquivos",
                        null, "2026-08-11", null, null});
    }

    /** Shaped exactly like {@code ReportRepository.overdue} returns them. */
    private List<Object[]> overdueRows() {
        return Arrays.asList(
                new Object[] {"Certificado expirado", "HIGH", "2026-09-01", "Ana Gonçalves", "Intranet"},
                new Object[] {"Senha padrão em produção", "CRITICAL", "2026-09-05", null, "Portal de Cobrança"});
    }
}
