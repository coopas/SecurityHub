package com.securityhub.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.asset.Asset;
import com.securityhub.project.Project;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.vulnerability.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two things are verified here, and they are two halves of the same subject.
 *
 * <p>The first is a regression test with a name attached to it. {@code /actuator/metrics} used
 * to be exposed on the application port and was not in {@code SecurityConfig.PUBLIC_ENDPOINTS},
 * so it fell to {@code anyRequest().authenticated()} — which means <em>any</em> authenticated
 * user of <em>any</em> role, a VIEWER included, could read every URI template of the server,
 * the per-endpoint request counts, the state of the connection pool and the JVM version. The
 * fix was not to put the path behind {@code hasRole('ADMIN')}: the ADMIN of this product is a
 * customer's administrator, not an operator, and operator metrics are not theirs either. The
 * endpoints moved to their own port, and the tests below assert that the tenant port no longer
 * answers for them at all.
 *
 * <p>The second is the three custom meters, each asserted through the real HTTP path that
 * feeds it, plus the rule that keeps the registry from becoming an outage of its own: no meter
 * may carry a per-tenant tag.
 */
class ObservabilityIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String VULNERABILITIES = "/api/v1/vulnerabilities";

    private static final byte[] PDF =
            "%PDF-1.7\nprova de correção\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    private static final Path UPLOAD_DIRECTORY = createUploadDirectory();

    @DynamicPropertySource
    static void attachmentProperties(DynamicPropertyRegistry registry) {
        registry.add("securityhub.attachments.directory", UPLOAD_DIRECTORY::toString);
    }

    @Autowired
    private MeterRegistry meterRegistry;

    private TestDataFactory.Tenant acme;
    private long vulnerabilityId;

    @BeforeEach
    void createTenant() {
        acme = fixtures.tenant("acme");
        Project portal = fixtures.project(acme.company, "Portal");
        Asset api = fixtures.asset(portal, "API de Cobrança");
        vulnerabilityId = fixtures.openVulnerability(api, "SQL Injection", Severity.HIGH, Instant.now())
                .getId();
    }

    // --- the leak that is now closed -----------------------------------------

    /**
     * The regression test for the VIEWER leak. A 404 and not a 403: the handler does not exist
     * on this port, which is a stronger statement than "you are not allowed" — there is nothing
     * on the tenant port to be allowed to read.
     */
    @Test
    void metricsAreNotServedOnTheApplicationPort() throws Exception {
        mockMvc.perform(get("/actuator/metrics").header("Authorization", fixtures.bearer(acme.viewer)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/metrics").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound());
    }

    /** Health moved with the rest: no handler for it on this port either. */
    @Test
    void healthIsNotServedOnTheApplicationPort() throws Exception {
        mockMvc.perform(get("/actuator/health").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health/readiness").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound());
    }

    /**
     * The probe's regression test, and it exists because the obvious reasoning about the
     * management port is wrong.
     *
     * <p>It is tempting to conclude that, once the actuator moved to {@code
     * management.server.port}, the {@code /actuator/health} entries of {@code PUBLIC_ENDPOINTS}
     * became dead matchers — that the management child context is outside this filter chain.
     * Under Boot 2.7 it is not: the child context inherits the parent's {@code
     * springSecurityFilterChain}, so this chain governs both ports. Removing the entries makes
     * {@code /actuator/health/readiness} answer 401 on the management port, the container
     * healthcheck never turns healthy, and the frontend — which depends on it — never starts.
     * That is exactly what happened on the compose stack before this test existed.
     *
     * <p>MockMvc only serves the application port, so the 401 cannot be reproduced here
     * directly. What can be asserted is the thing that causes it: an unauthenticated
     * {@code /actuator/health} must not be rejected by the security chain. A 404 means the
     * request passed the chain and simply found no handler on this port; a 401 would mean the
     * matcher is gone and the probe is broken.
     */
    @Test
    void healthIsNeverRejectedByTheSecurityChain() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());

        // E o contraste que prova que o matcher é o que faz a diferença: /actuator/metrics não
        // é público, então sem token a cadeia o recusa antes de qualquer handler.
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    // --- the three meters ----------------------------------------------------

    @Test
    void failedLoginIncrementsTheFailureOutcome() throws Exception {
        double before = loginCount("failure");

        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acme.test\",\"password\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized());

        // An unknown e-mail counts too: credential stuffing sprays addresses that do not exist,
        // and a counter blind to those would miss the shape of the attack it exists to show.
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ninguem@acme.test\",\"password\":\"senha-errada\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(loginCount("failure")).isEqualTo(before + 2);
    }

    @Test
    void successfulLoginIncrementsTheSuccessOutcome() throws Exception {
        double before = loginCount("success");
        double failuresBefore = loginCount("failure");

        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acme.test\",\"password\":\""
                                + TestDataFactory.DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        assertThat(loginCount("success")).isEqualTo(before + 1);
        assertThat(loginCount("failure")).isEqualTo(failuresBefore);
    }

    @Test
    void exportRecordsTheNumberOfRows() throws Exception {
        DistributionSummary summary = exportSummary();
        long countBefore = summary == null ? 0L : summary.count();
        double totalBefore = summary == null ? 0d : summary.totalAmount();

        mockMvc.perform(get(VULNERABILITIES + "/export")
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isOk());

        DistributionSummary after = exportSummary();
        assertThat(after).isNotNull();
        assertThat(after.count()).isEqualTo(countBefore + 1);
        // The single finding created by the fixture is the whole result set.
        assertThat(after.totalAmount()).isEqualTo(totalBefore + 1d);
        assertThat(after.getId().getTag("resource")).isEqualTo("vulnerability");
    }

    @Test
    void uploadIncrementsStoredBytesByTheFileSize() throws Exception {
        double before = storedBytes();

        mockMvc.perform(multipart(VULNERABILITIES + "/" + vulnerabilityId + "/attachments")
                        .file(new MockMultipartFile("file", "prova.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isCreated());

        assertThat(storedBytes()).isEqualTo(before + PDF.length);
    }

    // --- the tag rule --------------------------------------------------------

    /**
     * Tag cardinality multiplies the number of time series: one series per distinct combination
     * of tag values, kept forever. A {@code companyId} or {@code userId} tag is unbounded by
     * construction — every new customer, every new account, is a new series — and it is the
     * classic way to take a Prometheus down. Per-tenant attribution is what the audit trail is
     * for, and it already records the actor of every one of these three events.
     *
     * <p>The assertion covers the whole registry, not only the three meters of this application:
     * a future instrumentation added anywhere, including in a library, is caught here.
     */
    @Test
    void noMeterIsTaggedByTenantOrUser() {
        List<String> offenders = meterRegistry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream()
                        .filter(ObservabilityIntegrationTest::isTenantTag)
                        .map(tag -> meter.getId().getName() + " -> " + tag.getKey()))
                .distinct()
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("nenhuma métrica pode ser rotulada por tenant ou por usuário")
                .isEmpty();
    }

    @Test
    void theApplicationRegistersExactlyItsThreeCustomMeters() throws Exception {
        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acme.test\",\"password\":\""
                                + TestDataFactory.DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(VULNERABILITIES + "/export")
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isOk());
        mockMvc.perform(multipart(VULNERABILITIES + "/" + vulnerabilityId + "/attachments")
                        .file(new MockMultipartFile("file", "prova.pdf", MediaType.APPLICATION_PDF_VALUE, PDF))
                        .header("Authorization", fixtures.bearer(acme.analyst)))
                .andExpect(status().isCreated());

        List<String> custom = meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("securityhub."))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        assertThat(custom).containsExactly(
                "securityhub.attachments.bytes.stored",
                "securityhub.auth.login",
                "securityhub.export.rows");
    }

    // --- helpers -------------------------------------------------------------

    private static boolean isTenantTag(Tag tag) {
        String key = tag.getKey().toLowerCase(Locale.ROOT).replace("_", "").replace(".", "");
        return key.contains("companyid") || key.contains("userid") || key.equals("tenant");
    }

    private double loginCount(String outcome) {
        Counter counter = meterRegistry.find("securityhub.auth.login").tag("outcome", outcome).counter();
        return counter == null ? 0d : counter.count();
    }

    private DistributionSummary exportSummary() {
        return meterRegistry.find("securityhub.export.rows").summary();
    }

    private double storedBytes() {
        Counter counter = meterRegistry.find("securityhub.attachments.bytes.stored").counter();
        return counter == null ? 0d : counter.count();
    }

    /** Never {@code ./uploads}: a test must not write into the running application's directory. */
    private static Path createUploadDirectory() {
        try {
            return Files.createTempDirectory("securityhub-observability-uploads");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
