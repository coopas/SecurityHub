package com.securityhub.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.asset.Asset;
import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditLog;
import com.securityhub.audit.AuditLogRepository;
import com.securityhub.project.Project;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The report is a second reader of the dashboard's aggregations, and the two failures that would
 * matter most are invisible in the bytes: figures that drift away from the screen, and rows of
 * another tenant on a document meant to be forwarded by e-mail. Both are pinned here, on the text
 * actually extracted from the generated PDF.
 */
class ReportIntegrationTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/api/v1/reports/executive";

    @Autowired
    private AuditLogRepository auditLogRepository;

    private TestDataFactory.Tenant acme;
    private TestDataFactory.Tenant globex;

    @BeforeEach
    void seedBothTenants() {
        acme = fixtures.tenant("acme");
        Project portal = fixtures.project(acme.company, "Portal de Cobrança da Acme");
        Asset billing = fixtures.asset(portal, "API de Cobrança");
        Instant past = Instant.now().minus(20, ChronoUnit.DAYS);
        fixtures.openVulnerability(billing, "Injeção de SQL da Acme", Severity.CRITICAL, past);
        fixtures.vulnerability(billing, "Certificado vencido da Acme", Severity.HIGH,
                VulnerabilityStatus.OPEN, past, past.plus(1, ChronoUnit.DAYS), null);
        fixtures.vulnerability(billing, "Falha corrigida da Acme", Severity.LOW,
                VulnerabilityStatus.RESOLVED, past, null, Instant.now());

        globex = fixtures.tenant("globex");
        Project intranet = fixtures.project(globex.company, "Intranet da Globex");
        Asset fileServer = fixtures.asset(intranet, "Servidor de Arquivos");
        fixtures.openVulnerability(fileServer, "Segredo da Globex", Severity.CRITICAL, past);
    }

    // --- authorization ------------------------------------------------------

    @Test
    void adminAndAnalystDownloadThePdf() throws Exception {
        String expectedName = "relatorio-executivo-acme-"
                + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.now().atOffset(ZoneOffset.UTC))
                + ".pdf";

        for (User allowed : Arrays.asList(acme.admin, acme.analyst)) {
            MockHttpServletResponse response = generate(allowed);

            assertThat(response.getContentType()).isEqualTo("application/pdf");
            assertThat(response.getHeader("Content-Disposition"))
                    .startsWith("attachment;")
                    .contains("filename*=UTF-8''" + expectedName);
            assertThat(new String(Arrays.copyOf(response.getContentAsByteArray(), 5),
                    StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }
    }

    @Test
    void developerAndViewerAreRefused() throws Exception {
        for (User denied : Arrays.asList(acme.developer, acme.viewer)) {
            mockMvc.perform(get(ENDPOINT).header("Authorization", fixtures.bearer(denied)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    // --- tenant isolation ---------------------------------------------------

    /**
     * The document is the one artefact of this system that is meant to leave it, so a row of
     * another company on it is not a leak between screens but a leak into an attachment.
     */
    @Test
    void neverPrintsAnythingBelongingToAnotherCompany() throws Exception {
        String text = extract(generate(globex.admin));

        assertThat(text).contains("Segredo da Globex").contains("Intranet da Globex");
        assertThat(text)
                .doesNotContain("Injeção de SQL da Acme")
                .doesNotContain("Certificado vencido da Acme")
                .doesNotContain("Falha corrigida da Acme")
                .doesNotContain("Portal de Cobrança da Acme");
    }

    // --- the figures --------------------------------------------------------

    /**
     * The one property the whole design exists to keep: the numbers on the PDF are the numbers
     * the dashboard answers, because they come from the same service call and are not recomputed.
     */
    @Test
    void theScalarsAreTheOnesTheDashboardAnswers() throws Exception {
        JsonNode summary = objectMapper.readTree(mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        String text = extract(generate(acme.admin));

        assertThat(text)
                .contains("Total de vulnerabilidades " + summary.get("totalVulnerabilities").asLong())
                .contains("Em aberto (OPEN + IN_PROGRESS) " + summary.get("openVulnerabilities").asLong())
                .contains("Críticas em aberto " + summary.get("criticalOpenVulnerabilities").asLong())
                .contains("Atrasadas " + summary.get("overdueVulnerabilities").asLong())
                .contains("Resolvidas " + summary.get("resolvedVulnerabilities").asLong())
                .contains("Projetos " + summary.get("totalProjects").asLong())
                .contains("Ativos " + summary.get("totalAssets").asLong());
    }

    /** The two queries the report adds to the reused aggregations. */
    @Test
    void listsTheCriticalOpenAndTheOverdueFindings() throws Exception {
        String text = extract(generate(acme.analyst));

        assertThat(text)
                .contains("Vulnerabilidades críticas em aberto")
                .contains("Injeção de SQL da Acme")
                .contains("Atrasadas (prazo vencido)")
                .contains("Certificado vencido da Acme");
        // RESOLVED, so it is neither critical-open nor late however old its due date is.
        assertThat(text).doesNotContain("Falha corrigida da Acme");
    }

    // --- audit --------------------------------------------------------------

    /**
     * The insert has to survive the {@code readOnly = true} transaction the generation runs in,
     * which is why the service records it independently; a row missing here means it did not.
     */
    @Test
    void recordsExactlyOneExportEntryOnReport() throws Exception {
        generate(acme.admin);

        List<AuditLog> exports = auditLogRepository.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.EXPORT)
                .collect(Collectors.toList());
        assertThat(exports).hasSize(1);

        AuditLog entry = exports.get(0);
        assertThat(entry.getEntityType()).isEqualTo("Report");
        assertThat(entry.getEntityId()).isNull();
        assertThat(entry.getActorEmail()).isEqualTo(acme.admin.getEmail());

        JsonNode values = objectMapper.readTree(entry.getNewValueJson());
        assertThat(values.get("report").asText()).isEqualTo("executive");
        assertThat(values.get("format").asText()).isEqualTo("pdf");
        assertThat(values.get("sizeBytes").asLong()).isGreaterThan(0L);
    }

    // --- helpers ------------------------------------------------------------

    private MockHttpServletResponse generate(User actor) throws Exception {
        return mockMvc.perform(get(ENDPOINT).header("Authorization", fixtures.bearer(actor)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    /** Whitespace collapsed for the same reason as in {@code ExecutiveReportPdfWriterTest}. */
    private String extract(MockHttpServletResponse response) throws IOException {
        try (PDDocument document = PDDocument.load(response.getContentAsByteArray())) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }
}
