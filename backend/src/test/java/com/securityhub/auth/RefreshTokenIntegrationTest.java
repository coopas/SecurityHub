package com.securityhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cobre a tabela de decisão do ADR 0006 de ponta a ponta, pelo HTTP.
 *
 * As linhas são envelhecidas por SQL direto em vez de esperar o relógio: a janela de graça tem
 * 30 segundos e o vencimento tem 14 dias, então um teste que aguardasse seria lento no primeiro
 * caso e impossível no segundo.
 */
class RefreshTokenIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void loginReturnsARefreshTokenAndStoresOnlyItsHash() throws Exception {
        fixtures.tenant("acme");

        String refreshToken = login("admin@acme.test").get("refreshToken").asText();

        assertThat(refreshToken).isNotEmpty();
        List<String> stored = jdbcTemplate.queryForList("SELECT token_hash FROM refresh_tokens",
                String.class);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).isEqualTo(SecretTokens.hash(refreshToken)).matches("^[0-9a-f]{64}$");
        // O valor em claro não existe em lugar nenhum do banco.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, refreshToken))
                .isZero();
    }

    @Test
    void registerAlsoOpensASession() throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("companyName", "Acme");
        payload.put("name", "Administrador");
        payload.put("email", "admin@acme.test");
        payload.put("password", TestDataFactory.DEFAULT_PASSWORD);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void refreshRotatesAndInvalidatesThePresentedToken() throws Exception {
        fixtures.tenant("acme");
        String first = login("admin@acme.test").get("refreshToken").asText();

        JsonNode refreshed = refresh(first, status().isOk());
        String second = refreshed.get("refreshToken").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(refreshed.get("accessToken").asText()).isNotEmpty();
        // A linha apresentada sobrevive como ROTATED: é ela que permite reconhecer o reuso.
        assertThat(statusOf(first)).isEqualTo("ROTATED");
        assertThat(statusOf(second)).isEqualTo("ACTIVE");
        assertThat(familyOf(first)).isEqualTo(familyOf(second));
    }

    @Test
    void theAccessTokenFromARefreshIsUsableImmediately() throws Exception {
        fixtures.tenant("acme");
        String refreshToken = login("admin@acme.test").get("refreshToken").asText();

        String accessToken = refresh(refreshToken, status().isOk()).get("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@acme.test"));
    }

    @Test
    void reusingARotatedTokenOutsideTheGraceWindowKillsTheWholeFamily() throws Exception {
        fixtures.tenant("acme");
        String first = login("admin@acme.test").get("refreshToken").asText();
        String second = refresh(first, status().isOk()).get("refreshToken").asText();
        ageUsedAt(first, 120);

        refresh(first, status().isUnauthorized());

        // O sucessor legítimo morre junto: em um roubo, ele é justamente o que o ladrão pode
        // ter em mãos, e manter a família meio viva não protegeria ninguém.
        assertThat(statusOf(first)).isEqualTo("REVOKED");
        assertThat(statusOf(second)).isEqualTo("REVOKED");
        assertThat(reasonOf(second)).isEqualTo("REUSE_DETECTED");
        refresh(second, status().isUnauthorized());
    }

    @Test
    void theFamilyRevocationSurvivesTheRejectionItCausedAndIsAudited() throws Exception {
        fixtures.tenant("acme");
        String first = login("admin@acme.test").get("refreshToken").asText();
        refresh(first, status().isOk());
        ageUsedAt(first, 120);

        refresh(first, status().isUnauthorized());

        // A recusa é uma exceção; sem noRollbackFor ela desfaria a própria defesa.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE status = 'REVOKED'", Long.class))
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_REUSE_DETECTED'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void reusingWithinTheGraceWindowIssuesAnotherTokenInTheSameFamily() throws Exception {
        fixtures.tenant("acme");
        String first = login("admin@acme.test").get("refreshToken").asText();
        String second = refresh(first, status().isOk()).get("refreshToken").asText();

        // Segunda aba: o mesmo token chega de novo em segundos. A janela existe para que isso
        // não seja tratado como roubo.
        String third = refresh(first, status().isOk()).get("refreshToken").asText();

        assertThat(third).isNotEqualTo(first).isNotEqualTo(second);
        assertThat(familyOf(third)).isEqualTo(familyOf(first));
        assertThat(statusOf(second)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE status = 'REVOKED'", Long.class))
                .isZero();
    }

    @Test
    void logoutRevokesOnlyThePresentedFamily() throws Exception {
        fixtures.tenant("acme");
        String sessionA = login("admin@acme.test").get("refreshToken").asText();
        String sessionB = login("admin@acme.test").get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(sessionA)))
                .andExpect(status().isNoContent());

        assertThat(statusOf(sessionA)).isEqualTo("REVOKED");
        assertThat(reasonOf(sessionA)).isEqualTo("LOGOUT");
        assertThat(statusOf(sessionB)).isEqualTo("ACTIVE");
        refresh(sessionB, status().isOk());
    }

    @Test
    void logoutOfAnUnknownTokenStillAnswersNoContent() throws Exception {
        fixtures.tenant("acme");

        // 204 também aqui: um 404 diria ao chamador que aquele token não existe, e transformaria
        // o logout em um oráculo de existência de sessão.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody("token-inventado")))
                .andExpect(status().isNoContent());
    }

    @Test
    void anAccessTokenPresentedAtRefreshIsRejected() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        // O JWT hasheia para um digest que não existe na tabela: a confusão de tipos do ADR 0006
        // é impossível por construção, não por uma claim extra.
        refresh(fixtures.token(tenant.admin), status().isUnauthorized());
    }

    @Test
    void aRefreshTokenPresentedAsABearerTokenIsRejected() throws Exception {
        fixtures.tenant("acme");
        String refreshToken = login("admin@acme.test").get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredRefreshTokenIsRejected() throws Exception {
        fixtures.tenant("acme");
        String refreshToken = login("admin@acme.test").get("refreshToken").asText();
        expire(refreshToken);

        refresh(refreshToken, status().isUnauthorized());
        // Vencido não vira revogado: nada a revogar, e a linha ainda descreve o que houve.
        assertThat(statusOf(refreshToken)).isEqualTo("ACTIVE");
    }

    @Test
    void everyRejectionIsIndistinguishable() throws Exception {
        fixtures.tenant("acme");

        String unknown = body(refresh("nao-existe-este-token", status().isUnauthorized()));

        String expired = login("admin@acme.test").get("refreshToken").asText();
        expire(expired);
        String expiredBody = body(refresh(expired, status().isUnauthorized()));

        String revoked = login("admin@acme.test").get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON).content(refreshBody(revoked)));
        String revokedBody = body(refresh(revoked, status().isUnauthorized()));

        String reused = login("admin@acme.test").get("refreshToken").asText();
        refresh(reused, status().isOk());
        ageUsedAt(reused, 120);
        String reusedBody = body(refresh(reused, status().isUnauthorized()));

        assertThat(List.of(unknown, expiredBody, revokedBody, reusedBody))
                .allMatch(response -> response.equals(unknown));
        assertThat(unknown).contains("Sessão inválida").contains("UNAUTHORIZED");
    }

    @Test
    void deactivationKillsEveryRefreshTokenOfThatUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String refreshToken = login("analyst@acme.test").get("refreshToken").asText();

        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId() + "/active")
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        assertThat(reasonOf(refreshToken)).isEqualTo("USER_DEACTIVATED");
        refresh(refreshToken, status().isUnauthorized());
    }

    @Test
    void aRoleChangeKillsEveryRefreshTokenOfThatUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String refreshToken = login("analyst@acme.test").get("refreshToken").asText();

        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId() + "/role")
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        assertThat(reasonOf(refreshToken)).isEqualTo("ROLE_CHANGED");
        refresh(refreshToken, status().isUnauthorized());
    }

    @Test
    void aSuccessfulRefreshWritesNoAuditRow() throws Exception {
        fixtures.tenant("acme");
        String refreshToken = login("admin@acme.test").get("refreshToken").asText();
        long before = auditCount();

        refresh(refreshToken, status().isOk());

        // Uma linha por hora por sessão de ruído sobre um fato que o LOGIN já registrou.
        assertThat(auditCount()).isEqualTo(before);
    }

    @Test
    void loginCollectsRowsExpiredForMoreThanSevenDays() throws Exception {
        fixtures.tenant("acme");
        String stale = login("admin@acme.test").get("refreshToken").asText();
        String recentlyExpired = login("admin@acme.test").get("refreshToken").asText();
        setExpiresAt(stale, Instant.now().minus(30, ChronoUnit.DAYS));
        setExpiresAt(recentlyExpired, Instant.now().minus(1, ChronoUnit.DAYS));

        login("admin@acme.test");

        assertThat(exists(stale)).isFalse();
        // Dentro da folga de 7 dias a linha fica: ela ainda serve a uma investigação.
        assertThat(exists(recentlyExpired)).isTrue();
    }

    @Test
    void theCollectionNeverTouchesAnotherUsersRows() throws Exception {
        fixtures.tenant("acme");
        String analystToken = login("analyst@acme.test").get("refreshToken").asText();
        setExpiresAt(analystToken, Instant.now().minus(30, ChronoUnit.DAYS));

        login("admin@acme.test");

        assertThat(exists(analystToken)).isTrue();
    }

    @Test
    void refreshingAnInactiveUsersSurvivingTokenRevokesEverythingOfTheirs() throws Exception {
        var company = fixtures.company("Acme", "acme");
        User user = fixtures.user(company, "analista@acme.test", Role.ANALYST);
        String refreshToken = login("analista@acme.test").get("refreshToken").asText();
        // Desativação por fora da API, que é o único jeito de a linha sobreviver ao evento.
        jdbcTemplate.update("UPDATE users SET active = FALSE WHERE id = ?", user.getId());

        refresh(refreshToken, status().isUnauthorized());

        assertThat(statusOf(refreshToken)).isEqualTo("REVOKED");
        assertThat(reasonOf(refreshToken)).isEqualTo("USER_DEACTIVATED");
    }

    @Test
    void refreshValidatesThePayload() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- helpers -------------------------------------------------------------

    private JsonNode login(String email) throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("password", TestDataFactory.DEFAULT_PASSWORD);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private JsonNode refresh(String token, org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(refreshBody(token)))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private String body(JsonNode node) {
        // O timestamp e o traceId variam entre respostas; o que precisa ser idêntico é o resto.
        return node.get("status").asText() + "|" + node.get("code").asText() + "|"
                + node.get("message").asText();
    }

    private String refreshBody(String token) {
        Map<String, String> payload = new HashMap<>();
        payload.put("refreshToken", token);
        return json(payload);
    }

    private String statusOf(String token) {
        return jdbcTemplate.queryForObject("SELECT status FROM refresh_tokens WHERE token_hash = ?",
                String.class, SecretTokens.hash(token));
    }

    private String reasonOf(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                SecretTokens.hash(token));
    }

    private String familyOf(String token) {
        return jdbcTemplate.queryForObject("SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                String.class, SecretTokens.hash(token));
    }

    private boolean exists(String token) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?",
                Long.class, SecretTokens.hash(token)) > 0;
    }

    private void ageUsedAt(String token, long seconds) {
        jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(seconds)), SecretTokens.hash(token));
    }

    private void expire(String token) {
        setExpiresAt(token, Instant.now().minusSeconds(60));
    }

    private void setExpiresAt(String token, Instant when) {
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(when), SecretTokens.hash(token));
    }

    private long auditCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
    }
}
