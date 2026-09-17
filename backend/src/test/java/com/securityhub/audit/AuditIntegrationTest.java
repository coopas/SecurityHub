package com.securityhub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * is implemented — every mutation of auth, project, asset, vulnerability and
 * comment calls {@code auditService.record} — and this class is what proves it, from the
 * outside, through the API and the repository.
 */
class AuditIntegrationTest extends AbstractIntegrationTest {

    private static final String AUDIT = "/api/v1/audit-logs";
    private static final String PROJECTS = "/api/v1/projects";
    private static final String ASSETS = "/api/v1/assets";
    private static final String VULNERABILITIES = "/api/v1/vulnerabilities";

    /**
     * Mirrors the private denylist of {@link AuditSanitizer}. Duplicated on purpose: a test
     * that read the production list could not notice the day a fragment is deleted from it.
     */
    private static final List<String> SENSITIVE_FRAGMENTS = Arrays.asList(
            "password", "senha", "passwordhash", "hash", "token", "secret", "credential",
            "authorization", "apikey", "api_key", "otp", "cvv");

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestDataFactory.Tenant acme;
    private TestDataFactory.Tenant globex;

    @BeforeEach
    void createTenants() {
        acme = fixtures.tenant("acme");
        globex = fixtures.tenant("globex");
    }

    // --- authorization ------------------------------------------------------

    @Test
    void onlyAdminMayQueryTheTrail() throws Exception {
        mockMvc.perform(get(AUDIT).header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        for (User denied : Arrays.asList(acme.analyst, acme.developer, acme.viewer)) {
            mockMvc.perform(get(AUDIT).header("Authorization", fixtures.bearer(denied)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        mockMvc.perform(get(AUDIT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * The actor filter must be intersected with the company scope, not replace it. An empty
     * page — and not a 403 or a 404 — is the right answer: the caller may not learn whether
     * that id exists in another tenant.
     */
    @Test
    void companyScopingHidesAnotherTenantAndIntersectsTheActorFilter() throws Exception {
        long acmeProject = createProject(acme.admin, "Portal");
        long globexProject = createProject(globex.admin, "Billing");
        assertThat(acmeProject).isNotEqualTo(globexProject);

        JsonNode acmePage = page(AUDIT, acme.admin);
        assertThat(acmePage.get("totalElements").asLong()).isEqualTo(1L);
        assertThat(acmePage.get("content").get(0).get("actorEmail").asText())
                .isEqualTo(acme.admin.getEmail());

        // Every row of the page belongs to acme's admin; globex's row is simply not there.
        for (JsonNode entry : acmePage.get("content")) {
            assertThat(entry.get("actorId").asLong()).isEqualTo(acme.admin.getId());
        }

        mockMvc.perform(get(AUDIT + "?actorId=" + globex.admin.getId())
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        // The row does exist — for its own tenant.
        mockMvc.perform(get(AUDIT + "?actorId=" + globex.admin.getId())
                        .header("Authorization", fixtures.bearer(globex.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- the Phase 6 acceptance criterion -----------------------------------

    /**
     * The audit requirement: "uma mudança de status exibe ator, horário, entidade, valor anterior e
     * novo sem dados sensíveis".
     */
    @Test
    void aStatusChangeRecordsActorTimestampEntityAndBothValues() throws Exception {
        long project = createProject(acme.admin, "Portal");
        long asset = createAsset(acme.admin, project, "API");
        long vulnerability = createVulnerability(acme.analyst, asset, "SQL Injection");

        Instant beforeChange = Instant.now().minusSeconds(1);
        mockMvc.perform(patch(VULNERABILITIES + "/" + vulnerability + "/status")
                        .header("Authorization", fixtures.bearer(acme.analyst))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isOk());

        JsonNode entry = page(AUDIT + "?entityType=Vulnerability&action=STATUS_CHANGE", acme.admin)
                .get("content").get(0);

        assertThat(entry.get("actorId").asLong()).isEqualTo(acme.analyst.getId());
        assertThat(entry.get("actorEmail").asText()).isEqualTo(acme.analyst.getEmail());
        assertThat(entry.get("action").asText()).isEqualTo("STATUS_CHANGE");
        assertThat(entry.get("entityType").asText()).isEqualTo("Vulnerability");
        assertThat(entry.get("entityId").asLong()).isEqualTo(vulnerability);
        assertThat(Instant.parse(entry.get("createdAt").asText())).isAfter(beforeChange);
        assertThat(entry.get("oldValue").get("status").asText()).isEqualTo("OPEN");
        assertThat(entry.get("newValue").get("status").asText()).isEqualTo("RESOLVED");
        // The trail is serialised with non_null inclusion, so "was not set" is an absent key
        // rather than an explicit null: before the change there was no resolution instant,
        // after it there is one.
        assertThat(entry.get("oldValue").has("resolvedAt")).isFalse();
        assertThat(entry.get("newValue").get("resolvedAt").asText()).isNotEmpty();
        assertThat(Instant.parse(entry.get("newValue").get("resolvedAt").asText()))
                .isAfter(beforeChange);
    }

    // --- append-only --------------------------------------------------------

    @Test
    void theTrailIsAppendOnlyOverHttp() throws Exception {
        createProject(acme.admin, "Portal");
        long before = auditLogRepository.count();
        assertThat(before).isPositive();

        String token = fixtures.bearer(acme.admin);
        String body = "{\"action\":\"DELETE\"}";

        // The collection is mapped for GET only, so anything else is a 405 and not a 404:
        // the resource exists, the verb does not.
        mockMvc.perform(post(AUDIT).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(AUDIT).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(AUDIT).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(AUDIT).header("Authorization", token))
                .andExpect(status().isMethodNotAllowed());

        // There is no per-row endpoint at all, for any verb.
        long id = auditLogRepository.findAll().get(0).getId();
        mockMvc.perform(post(AUDIT + "/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(AUDIT + "/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(AUDIT + "/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(AUDIT + "/" + id).header("Authorization", token))
                .andExpect(status().isNotFound());

        assertThat(auditLogRepository.count()).isEqualTo(before);
    }

    /**
     * Append-only has to hold at the layer where it is actually enforced. A missing HTTP verb
     * is a routing decision someone can undo in one line; a table without {@code updated_at}
     * has nowhere to record a modification in the first place, and {@code AuditLog}
     * deliberately does not extend {@code BaseEntity} for exactly that reason.
     */
    @Test
    void theAuditTableHasNoUpdatedAtColumn() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'audit_logs'",
                String.class);

        assertThat(columns).contains("created_at").doesNotContain("updated_at");
    }

    // --- secrets ------------------------------------------------------------

    /**
     * Read straight from the repository and not through {@code GET /audit-logs}: the API is
     * the thing under suspicion here, so asking it what it stored would prove nothing.
     *
     * Limitation, stated on purpose: this asserts that no credential used in the flow ever
     * appears in a value, and that key-based masking works for every key the sanitizer
     * claims to cover. It deliberately does NOT forbid the substring "token" inside a value —
     * a finding legitimately titled "JWT token leakage in /auth/login" has to be storable,
     * and a test that banned the word would force the product to lose real data.
     */
    @Test
    void noSecretEverReachesTheTrail() throws Exception {
        String password = "Vazamento-Nunca-2026!";
        String wrongPassword = "Senha-Totalmente-Errada-9";

        String registerBody = "{\"companyName\":\"Umbrella\",\"name\":\"Alice\","
                + "\"email\":\"alice@umbrella.test\",\"password\":\"" + password + "\"}";
        String registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + objectMapper.readTree(registered).get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@umbrella.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@umbrella.test\",\"password\":\"" + wrongPassword + "\"}"))
                .andExpect(status().isUnauthorized());

        long project = createProject(token, "Produção");
        long asset = createAsset(token, project, "API pública");
        long vulnerability = createVulnerability(token, asset, "Credencial no repositório");
        mockMvc.perform(put(VULNERABILITIES + "/" + vulnerability)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + asset + ",\"title\":\"Credencial no repositório (revisada)\","
                                + "\"severity\":\"CRITICAL\"}"))
                .andExpect(status().isOk());
        // A comment that carries a credential in its body. CommentService stores only the
        // length of the text, which is why this cannot reach the trail.
        mockMvc.perform(post(VULNERABILITIES + "/" + vulnerability + "/comments")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Collections.singletonMap(
                                "content", "Achado: password=" + password
                                        + " e Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.falso.assinatura"))))
                .andExpect(status().isCreated());

        List<AuditLog> rows = auditLogRepository.findAll();
        assertThat(rows).hasSizeGreaterThanOrEqualTo(6);

        for (AuditLog row : rows) {
            for (String json : Arrays.asList(row.getOldValueJson(), row.getNewValueJson())) {
                if (json == null) {
                    continue;
                }
                assertThat(json)
                        .as("linha %s/%s", row.getAction(), row.getEntityType())
                        .doesNotContain(password)
                        .doesNotContain(wrongPassword)
                        // BCrypt hashes, in either of the two prefixes the encoder emits.
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        // The header of every JWT this application signs.
                        .doesNotContain("eyJ");
                assertMaskedKeys(objectMapper.readTree(json), row);
            }
        }
    }

    /** Every key the sanitizer claims to cover must hold the redaction marker, not a value. */
    private void assertMaskedKeys(JsonNode node, AuditLog row) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey())) {
                    assertThat(field.getValue().asText())
                            .as("chave %s da linha %s", field.getKey(), row.getId())
                            .isEqualTo(AuditSanitizer.REDACTED);
                } else {
                    assertMaskedKeys(field.getValue(), row);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                assertMaskedKeys(item, row);
            }
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        return SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    @Test
    void authenticationEventsCarryNoValueJsons() throws Exception {
        String password = "Autenticacao-2026!";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"Initech\",\"name\":\"Bob\","
                                + "\"email\":\"bob@initech.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@initech.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@initech.test\",\"password\":\"errada-errada-1\"}"))
                .andExpect(status().isUnauthorized());

        List<AuditAction> authActions =
                Arrays.asList(AuditAction.REGISTER, AuditAction.LOGIN, AuditAction.LOGIN_FAILED);
        List<AuditLog> rows = auditLogRepository.findAll();

        assertThat(rows).extracting(AuditLog::getAction).containsAll(authActions);
        for (AuditLog row : rows) {
            if (authActions.contains(row.getAction())) {
                assertThat(row.getOldValueJson()).as("oldValue de %s", row.getAction()).isNull();
                assertThat(row.getNewValueJson()).as("newValue de %s", row.getAction()).isNull();
                assertThat(row.getActorEmail()).isEqualTo("bob@initech.test");
            }
        }
        // A failed login survives its own rollback because it is recorded independently.
        assertThat(rows).extracting(AuditLog::getAction).contains(AuditAction.LOGIN_FAILED);
    }

    // --- filters ---------------------------------------------------

    @Test
    void filtersNarrowAndCombine() throws Exception {
        long project = createProject(acme.admin, "Portal");
        long asset = createAsset(acme.admin, project, "API");
        long vulnerability = createVulnerability(acme.analyst, asset, "SQL Injection");
        mockMvc.perform(patch(VULNERABILITIES + "/" + vulnerability + "/status")
                        .header("Authorization", fixtures.bearer(acme.analyst))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        // 2 CREATE by the admin (project, asset) + 1 CREATE and 1 STATUS_CHANGE by the analyst.
        assertThat(total(AUDIT, acme.admin)).isEqualTo(4L);

        assertThat(total(AUDIT + "?entityType=Project", acme.admin)).isEqualTo(1L);
        assertThat(total(AUDIT + "?entityType=Asset", acme.admin)).isEqualTo(1L);
        assertThat(total(AUDIT + "?actorId=" + acme.admin.getId(), acme.admin)).isEqualTo(2L);
        assertThat(total(AUDIT + "?actorId=" + acme.analyst.getId(), acme.admin)).isEqualTo(2L);
        assertThat(total(AUDIT + "?action=CREATE", acme.admin)).isEqualTo(3L);
        assertThat(total(AUDIT + "?action=STATUS_CHANGE", acme.admin)).isEqualTo(1L);

        // Combined, the filters intersect rather than union.
        assertThat(total(AUDIT + "?entityType=Vulnerability&action=CREATE", acme.admin)).isEqualTo(1L);
        assertThat(total(AUDIT + "?entityType=Vulnerability&action=CREATE&actorId="
                + acme.admin.getId(), acme.admin)).isZero();

        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThat(total(AUDIT + "?from=" + past, acme.admin)).isEqualTo(4L);
        assertThat(total(AUDIT + "?to=" + future, acme.admin)).isEqualTo(4L);
        assertThat(total(AUDIT + "?from=" + past + "&to=" + future, acme.admin)).isEqualTo(4L);
        assertThat(total(AUDIT + "?from=" + future, acme.admin)).isZero();
        assertThat(total(AUDIT + "?to=" + past, acme.admin)).isZero();

        // A blank entityType is normalized to "no filter", not to a filter on the empty
        // string. Sent through .param so the value reaches the controller unencoded.
        assertThat(objectMapper.readTree(mockMvc.perform(get(AUDIT)
                        .param("entityType", "   ")
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("totalElements").asLong()).isEqualTo(4L);
    }

    @Test
    void aSortOutsideTheWhitelistFallsBackToCreatedAtDesc() throws Exception {
        long project = createProject(acme.admin, "Portal");
        createAsset(acme.admin, project, "API");

        // "companyId" is a real property of the entity and would otherwise reach Spring Data.
        JsonNode rejected = page(AUDIT + "?sort=companyId,asc", acme.admin);
        assertThat(rejected.get("sort").asText()).isEqualTo("createdAt,desc");
        List<Long> ids = rejected.findValues("id").stream()
                .map(JsonNode::asLong).collect(Collectors.toList());
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());

        // A property that is not even mapped must not become a 500 either.
        assertThat(page(AUDIT + "?sort=naoExiste,desc", acme.admin).get("sort").asText())
                .isEqualTo("createdAt,desc");

        // A whitelisted property is honoured.
        assertThat(page(AUDIT + "?sort=entityType,asc", acme.admin).get("sort").asText())
                .isEqualTo("entityType,asc");
    }

    // --- helpers ------------------------------------------------------------

    private JsonNode page(String url, User reader) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)
                        .header("Authorization", fixtures.bearer(reader)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private long total(String url, User reader) throws Exception {
        return page(url, reader).get("totalElements").asLong();
    }

    private long createProject(User actor, String name) throws Exception {
        return createProject(fixtures.bearer(actor), name);
    }

    private long createProject(String bearer, String name) throws Exception {
        String body = mockMvc.perform(post(PROJECTS).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createAsset(User actor, long projectId, String name) throws Exception {
        return createAsset(fixtures.bearer(actor), projectId, name);
    }

    private long createAsset(String bearer, long projectId, String name) throws Exception {
        String body = mockMvc.perform(post(ASSETS).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":" + projectId + ",\"name\":\"" + name + "\","
                                + "\"type\":\"API\",\"environment\":\"PRODUCTION\",\"criticality\":\"HIGH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createVulnerability(User actor, long assetId, String title) throws Exception {
        return createVulnerability(fixtures.bearer(actor), assetId, title);
    }

    private long createVulnerability(String bearer, long assetId, String title) throws Exception {
        String body = mockMvc.perform(post(VULNERABILITIES).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":" + assetId + ",\"title\":\"" + title + "\","
                                + "\"severity\":\"HIGH\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }
}
