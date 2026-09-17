package com.securityhub.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.asset.Asset;
import com.securityhub.project.Project;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dataset is built once per test from a single {@code now} captured in {@link #seed},
 * with every timestamp at noon UTC of a whole-day offset. Noon and not midnight: a row
 * written at the boundary would flip to the neighbouring bucket depending on the second the
 * suite happens to run.
 *
 * Two rows exist only to pin the trend window: one discovered 29 days ago (the oldest day a
 * 30-day window still contains) and one discovered 30 days ago (the first day it must not).
 */
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private static final String SUMMARY = "/api/v1/dashboard/summary";
    private static final String SEVERITY = "/api/v1/dashboard/severity-distribution";
    private static final String STATUS = "/api/v1/dashboard/status-distribution";
    private static final String TREND = "/api/v1/dashboard/trend";
    private static final String VULNERABILITIES = "/api/v1/vulnerabilities";

    private static final List<String> ALL_ENDPOINTS =
            Arrays.asList(SUMMARY, SEVERITY, STATUS, TREND + "?days=30");

    private TestDataFactory.Tenant acme;
    private TestDataFactory.Tenant globex;
    private TestDataFactory.Tenant emptyco;
    private LocalDate today;

    @BeforeEach
    void seed() {
        today = LocalDate.now(ZoneOffset.UTC);

        acme = fixtures.tenant("acme");
        Project portal = fixtures.project(acme.company, "Portal");
        Project mobile = fixtures.project(acme.company, "Mobile");
        // Third project with an asset but no finding: it must never appear in topProjects.
        Project legacy = fixtures.project(acme.company, "Legacy");
        Asset portalApi = fixtures.asset(portal, "API do Portal");
        Asset mobileApp = fixtures.asset(mobile, "App Android");
        fixtures.asset(legacy, "Banco legado");

        // Portal — 5 findings: 3 active (2 of them late), 1 resolved, 1 accepted.
        fixtures.vulnerability(portalApi, "RCE no upload", Severity.CRITICAL,
                VulnerabilityStatus.OPEN, day(0), day(1), null);
        fixtures.vulnerability(portalApi, "XSS refletido", Severity.HIGH,
                VulnerabilityStatus.IN_PROGRESS, day(1), day(2), null);
        // Due date in the future: active but not late.
        fixtures.vulnerability(portalApi, "CSRF no formulário", Severity.CRITICAL,
                VulnerabilityStatus.OPEN, day(2), day(-10), null);
        // Overdue date but already resolved: never late (docs/data-model.md).
        fixtures.vulnerability(portalApi, "Cabeçalho ausente", Severity.MEDIUM,
                VulnerabilityStatus.RESOLVED, day(5), day(4), day(3));
        // Overdue date but risk accepted: never late either.
        fixtures.vulnerability(portalApi, "TLS 1.1 habilitado", Severity.LOW,
                VulnerabilityStatus.ACCEPTED_RISK, day(29), day(20), null);

        // Mobile — 2 findings. The first is the row that sits just outside a 30-day window.
        fixtures.vulnerability(mobileApp, "Certificado não fixado", Severity.HIGH,
                VulnerabilityStatus.OPEN, day(30), null, null);
        // Discovered outside the window it is resolved in: proves the resolved series reads
        // resolved_at and not discovered_at.
        fixtures.vulnerability(mobileApp, "Log com dados pessoais", Severity.MEDIUM,
                VulnerabilityStatus.RESOLVED, day(29), null, day(0));

        // Noise tenant. Every date falls on a day acme has nothing on (10, 15 and 20 days
        // ago), so a leak does not only move a card — it also breaks the trend.
        globex = fixtures.tenant("globex");
        Project billing = fixtures.project(globex.company, "Billing");
        Asset gateway = fixtures.asset(billing, "Gateway");
        fixtures.vulnerability(gateway, "Chave fraca", Severity.CRITICAL,
                VulnerabilityStatus.OPEN, day(10), day(11), null);
        fixtures.vulnerability(gateway, "Diretório listável", Severity.LOW,
                VulnerabilityStatus.RESOLVED, day(20), null, day(15));
        fixtures.vulnerability(gateway, "Sessão longa", Severity.HIGH,
                VulnerabilityStatus.IN_PROGRESS, day(15), day(-5), null);

        emptyco = fixtures.tenant("emptyco");
    }

    /** Noon UTC of {@code daysAgo} days ago; a negative value means the future. */
    private Instant day(int daysAgo) {
        return today.minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(12, ChronoUnit.HOURS);
    }

    // --- summary ------------------------------------------------------------

    @Test
    void summaryReturnsTheExactScalarsOfTheTenant() throws Exception {
        mockMvc.perform(get(SUMMARY).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVulnerabilities").value(7))
                // OPEN + IN_PROGRESS: three in Portal and one in Mobile.
                .andExpect(jsonPath("$.openVulnerabilities").value(4))
                .andExpect(jsonPath("$.criticalOpenVulnerabilities").value(2))
                // Only the two active findings with a past due date.
                .andExpect(jsonPath("$.overdueVulnerabilities").value(2))
                .andExpect(jsonPath("$.resolvedVulnerabilities").value(2))
                .andExpect(jsonPath("$.totalProjects").value(3))
                .andExpect(jsonPath("$.totalAssets").value(3))
                // The per-status breakdown deliberately lives only in /status-distribution.
                .andExpect(jsonPath("$.inProgressVulnerabilities").doesNotExist())
                .andExpect(jsonPath("$.acceptedRiskVulnerabilities").doesNotExist());
    }

    @Test
    void summaryBreaksDownByProjectAndOmitsProjectsWithoutFindings() throws Exception {
        mockMvc.perform(get(SUMMARY).header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topProjects.length()").value(2))
                // Ordered by total desc: Portal has 5 findings, Mobile 2.
                .andExpect(jsonPath("$.topProjects[0].projectName").value("Portal"))
                .andExpect(jsonPath("$.topProjects[0].projectId").isNumber())
                .andExpect(jsonPath("$.topProjects[0].total").value(5))
                .andExpect(jsonPath("$.topProjects[0].open").value(3))
                .andExpect(jsonPath("$.topProjects[0].overdue").value(2))
                .andExpect(jsonPath("$.topProjects[1].projectName").value("Mobile"))
                .andExpect(jsonPath("$.topProjects[1].total").value(2))
                .andExpect(jsonPath("$.topProjects[1].open").value(1))
                .andExpect(jsonPath("$.topProjects[1].overdue").value(0));
        // "Legacy" has an asset but no finding and is therefore absent, not a zero row.
    }

    /**
     * The card and its own drill-down must agree. This is the regression guard for the single
     * definition of "overdue" of docs/data-model.md: if the dashboard predicate and
     * {@code VulnerabilitySpecifications.overdue} ever drift apart, this fails.
     */
    @Test
    void summaryOverdueCardEqualsTheOverdueFilterTotal() throws Exception {
        long card = objectMapper.readTree(body(SUMMARY, acme.admin)).get("overdueVulnerabilities").asLong();
        long filtered = objectMapper.readTree(
                        body(VULNERABILITIES + "?overdue=true&size=1", acme.admin))
                .get("totalElements").asLong();

        assertThat(card).isEqualTo(2L).isEqualTo(filtered);
    }

    // --- distributions ------------------------------------------------------

    @Test
    void severityDistributionReturnsEverySeverityInDeclarationOrder() throws Exception {
        mockMvc.perform(get(SEVERITY).header("Authorization", fixtures.bearer(acme.developer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Severity.values().length))
                .andExpect(jsonPath("$[0].severity").value("LOW"))
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[1].severity").value("MEDIUM"))
                .andExpect(jsonPath("$[1].count").value(2))
                .andExpect(jsonPath("$[2].severity").value("HIGH"))
                .andExpect(jsonPath("$[2].count").value(2))
                .andExpect(jsonPath("$[3].severity").value("CRITICAL"))
                .andExpect(jsonPath("$[3].count").value(2));
    }

    @Test
    void statusDistributionReturnsEveryStatusInDeclarationOrder() throws Exception {
        mockMvc.perform(get(STATUS).header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(VulnerabilityStatus.values().length))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].count").value(3))
                .andExpect(jsonPath("$[1].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[1].count").value(1))
                .andExpect(jsonPath("$[2].status").value("RESOLVED"))
                .andExpect(jsonPath("$[2].count").value(2))
                .andExpect(jsonPath("$[3].status").value("ACCEPTED_RISK"))
                .andExpect(jsonPath("$[3].count").value(1));
    }

    // --- trend --------------------------------------------------------------

    @Test
    void trendReturnsOnePointPerDayIncludingTheEmptyOnes() throws Exception {
        JsonNode response = objectMapper.readTree(body(TREND + "?days=30", acme.admin));

        assertThat(response.get("days").asInt()).isEqualTo(30);
        assertThat(response.get("to").asText()).isEqualTo(today.toString());
        assertThat(response.get("from").asText()).isEqualTo(today.minusDays(29).toString());
        assertThat(response.get("points")).hasSize(30);

        Map<LocalDate, long[]> series = series(response);
        assertThat(series.get(today)).containsExactly(1L, 1L);
        assertThat(series.get(today.minusDays(1))).containsExactly(1L, 0L);
        assertThat(series.get(today.minusDays(2))).containsExactly(1L, 0L);
        assertThat(series.get(today.minusDays(3))).containsExactly(0L, 1L);
        // A day with nothing at all is present with zeros rather than missing.
        assertThat(series.get(today.minusDays(4))).containsExactly(0L, 0L);
        assertThat(series.get(today.minusDays(5))).containsExactly(1L, 0L);
        assertThat(series.get(today.minusDays(29))).containsExactly(2L, 0L);

        long opened = series.values().stream().mapToLong(point -> point[0]).sum();
        long resolved = series.values().stream().mapToLong(point -> point[1]).sum();
        assertThat(opened).isEqualTo(6L);
        assertThat(resolved).isEqualTo(2L);
    }

    /**
     * "Log com dados pessoais" was discovered 29 days ago and resolved today. If the resolved
     * series were built from {@code discovered_at}, both counts would land on the same day and
     * the chart would claim the backlog was cleared a month ago.
     */
    @Test
    void trendCountsResolutionsOnResolvedAtAndNotOnDiscoveredAt() throws Exception {
        Map<LocalDate, long[]> series = series(objectMapper.readTree(body(TREND + "?days=30", acme.admin)));

        assertThat(series.get(today.minusDays(29))[0]).isEqualTo(2L);
        assertThat(series.get(today.minusDays(29))[1]).isZero();
        assertThat(series.get(today)[1]).isEqualTo(1L);
    }

    @Test
    void trendWindowExcludesTheThirtiethDayAndIncludesItAtThirtyOne() throws Exception {
        JsonNode thirty = objectMapper.readTree(body(TREND + "?days=30", acme.admin));
        assertThat(thirty.get("points")).hasSize(30);
        assertThat(totalOpened(thirty)).isEqualTo(6L);

        JsonNode thirtyOne = objectMapper.readTree(body(TREND + "?days=31", acme.admin));
        assertThat(thirtyOne.get("points")).hasSize(31);
        assertThat(thirtyOne.get("from").asText()).isEqualTo(today.minusDays(30).toString());
        // The row discovered exactly 30 days ago now appears, and only that one.
        assertThat(totalOpened(thirtyOne)).isEqualTo(7L);
        assertThat(series(thirtyOne).get(today.minusDays(30))).containsExactly(1L, 0L);
    }

    @Test
    void trendClampsDaysSilentlyAndEchoesTheEffectiveWindow() throws Exception {
        JsonNode tooSmall = objectMapper.readTree(body(TREND + "?days=0", acme.admin));
        assertThat(tooSmall.get("days").asInt()).isEqualTo(1);
        assertThat(tooSmall.get("points")).hasSize(1);
        assertThat(tooSmall.get("from").asText()).isEqualTo(tooSmall.get("to").asText());

        JsonNode negative = objectMapper.readTree(body(TREND + "?days=-7", acme.admin));
        assertThat(negative.get("days").asInt()).isEqualTo(1);

        JsonNode tooLarge = objectMapper.readTree(body(TREND + "?days=500", acme.admin));
        assertThat(tooLarge.get("days").asInt()).isEqualTo(90);
        assertThat(tooLarge.get("points")).hasSize(90);
        assertThat(tooLarge.get("from").asText()).isEqualTo(today.minusDays(89).toString());

        // The default of §8 when the parameter is omitted altogether.
        assertThat(objectMapper.readTree(body(TREND, acme.admin)).get("days").asInt()).isEqualTo(30);
    }

    /**
     * Clamping is for values the control can legitimately produce. A value that is not a
     * number at all is a malformed request and still answers 400, like everywhere else.
     */
    @Test
    void trendRejectsANonNumericDaysParameter() throws Exception {
        mockMvc.perform(get(TREND + "?days=abc").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    // --- authorization and isolation ---------------------------------------

    @Test
    void everyRoleReadsTheDashboardAndGetsTheSameBytes() throws Exception {
        for (String endpoint : ALL_ENDPOINTS) {
            String reference = body(endpoint, acme.admin);
            for (User reader : Arrays.asList(acme.analyst, acme.developer, acme.viewer)) {
                assertThat(body(endpoint, reader))
                        .as("%s lido por %s", endpoint, reader.getRole())
                        .isEqualTo(reference);
            }
        }
    }

    @Test
    void everyEndpointRequiresAToken() throws Exception {
        for (String endpoint : ALL_ENDPOINTS) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void anotherTenantsRowsNeverReachTheAggregates() throws Exception {
        // Read as globex first: its own numbers are right, so the isolation below is not
        // simply "the second tenant has no data".
        mockMvc.perform(get(SUMMARY).header("Authorization", fixtures.bearer(globex.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVulnerabilities").value(3))
                .andExpect(jsonPath("$.overdueVulnerabilities").value(1))
                .andExpect(jsonPath("$.topProjects.length()").value(1))
                .andExpect(jsonPath("$.topProjects[0].projectName").value("Billing"));

        // acme is unchanged by globex existing.
        mockMvc.perform(get(SUMMARY).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVulnerabilities").value(7))
                .andExpect(jsonPath("$.overdueVulnerabilities").value(2))
                .andExpect(jsonPath("$.totalProjects").value(3))
                .andExpect(jsonPath("$.totalAssets").value(3))
                .andExpect(jsonPath("$.topProjects.length()").value(2));

        // globex put rows on days 10, 15 and 20; acme has nothing on those days and must
        // still have nothing.
        Map<LocalDate, long[]> series = series(objectMapper.readTree(body(TREND + "?days=30", acme.admin)));
        for (int daysAgo : new int[] {10, 15, 20}) {
            assertThat(series.get(today.minusDays(daysAgo)))
                    .as("dia -%d do tenant acme", daysAgo)
                    .containsExactly(0L, 0L);
        }

        // And the distributions do not sum the two tenants either.
        long acmeSeverities = objectMapper.readTree(body(SEVERITY, acme.admin))
                .findValues("count").stream().mapToLong(JsonNode::asLong).sum();
        assertThat(acmeSeverities).isEqualTo(7L);
    }

    @Test
    void anEmptyCompanyGetsZerosAndStillGetsCompleteDistributionsAndTrend() throws Exception {
        mockMvc.perform(get(SUMMARY).header("Authorization", fixtures.bearer(emptyco.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVulnerabilities").value(0))
                .andExpect(jsonPath("$.openVulnerabilities").value(0))
                .andExpect(jsonPath("$.criticalOpenVulnerabilities").value(0))
                .andExpect(jsonPath("$.overdueVulnerabilities").value(0))
                .andExpect(jsonPath("$.resolvedVulnerabilities").value(0))
                .andExpect(jsonPath("$.totalProjects").value(0))
                .andExpect(jsonPath("$.totalAssets").value(0))
                .andExpect(jsonPath("$.topProjects.length()").value(0));

        mockMvc.perform(get(SEVERITY).header("Authorization", fixtures.bearer(emptyco.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].count").value(0))
                .andExpect(jsonPath("$[3].count").value(0));

        mockMvc.perform(get(STATUS).header("Authorization", fixtures.bearer(emptyco.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].count").value(0))
                .andExpect(jsonPath("$[3].count").value(0));

        JsonNode trend = objectMapper.readTree(body(TREND + "?days=30", emptyco.admin));
        assertThat(trend.get("points")).hasSize(30);
        assertThat(totalOpened(trend)).isZero();
    }

    // --- helpers ------------------------------------------------------------

    private String body(String endpoint, User reader) throws Exception {
        return mockMvc.perform(get(endpoint).header("Authorization", fixtures.bearer(reader)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** {@code date -> [opened, resolved]}, which reads better than a chain of json paths. */
    private Map<LocalDate, long[]> series(JsonNode trend) {
        Map<LocalDate, long[]> series = new HashMap<>();
        for (JsonNode point : trend.get("points")) {
            series.put(LocalDate.parse(point.get("date").asText()),
                    new long[] {point.get("opened").asLong(), point.get("resolved").asLong()});
        }
        return series;
    }

    private long totalOpened(JsonNode trend) {
        long total = 0;
        for (JsonNode point : trend.get("points")) {
            total += point.get("opened").asLong();
        }
        return total;
    }
}
