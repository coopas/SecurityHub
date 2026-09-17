package com.securityhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O token em claro só existe dentro do e-mail, que a suíte nunca espera (ADR 0007). Os testes
 * que precisam confirmar o link plantam um hash conhecido na linha — é exatamente o que o
 * serviço procura — em vez de interceptar a thread assíncrona.
 */
class PasswordResetIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "senha-nova-do-usuario";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    void requestAnswersAcceptedAndStoresOnlyTheHash() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        requestReset("admin@acme.test").andExpect(status().isAccepted());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM password_reset_tokens");
        assertThat(row.get("user_id")).isEqualTo(tenant.admin.getId());
        assertThat((String) row.get("token_hash")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void requestAnswersIdenticallyForKnownUnknownAndInactiveAddresses() throws Exception {
        var company = fixtures.company("Acme", "acme");
        fixtures.user(company, "conhecido@acme.test", Role.ANALYST);
        fixtures.inactiveUser(company, "inativo@acme.test", Role.ANALYST);

        String known = bodyOf(requestReset("conhecido@acme.test"));
        String unknown = bodyOf(requestReset("ninguem@acme.test"));
        String inactive = bodyOf(requestReset("inativo@acme.test"));

        // Byte a byte: o 202 vazio é a única resposta possível, ou o endpoint vira um
        // enumerador de contas do produto inteiro.
        assertThat(known).isEmpty();
        assertThat(unknown).isEqualTo(known);
        assertThat(inactive).isEqualTo(known);
        // E só o endereço conhecido e ativo deixou linha.
        assertThat(tokenCount()).isEqualTo(1L);
    }

    @Test
    void requestIsCaseInsensitiveOnTheAddress() throws Exception {
        fixtures.tenant("acme");

        requestReset("ADMIN@ACME.TEST").andExpect(status().isAccepted());

        assertThat(tokenCount()).isEqualTo(1L);
    }

    @Test
    void askingTwiceKeepsOnlyTheLatestLink() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        requestReset("admin@acme.test");
        String firstHash = singleHash();

        requestReset("admin@acme.test");

        // A unicidade de user_id em V7 é o que garante isto; o serviço apaga antes de inserir.
        assertThat(tokenCount()).isEqualTo(1L);
        assertThat(singleHash()).isNotEqualTo(firstHash);
        assertThat(userIdOfSingleToken()).isEqualTo(tenant.admin.getId());
    }

    @Test
    void confirmSetsTheNewPasswordAndAnswersNoContent() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        login("admin@acme.test", NEW_PASSWORD, status().isOk());
        login("admin@acme.test", TestDataFactory.DEFAULT_PASSWORD, status().isUnauthorized());
    }

    @Test
    void confirmReturnsNoSessionAtAll() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));

        String response = bodyOf(confirm(token, NEW_PASSWORD).andExpect(status().isNoContent()));

        // 204 e não uma AuthResponse: quem redefiniu a senha vai ao login com ela. Um link de
        // e-mail interceptado não pode virar sessão pronta.
        assertThat(response).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class))
                .isZero();
    }

    @Test
    void confirmIsSingleUse() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        confirm(token, "outra-senha-qualquer").andExpect(status().isBadRequest());

        assertThat(tokenCount()).isZero();
        login("admin@acme.test", NEW_PASSWORD, status().isOk());
    }

    @Test
    void confirmRejectsAnExpiredToken() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().minusSeconds(60));

        confirm(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Link de redefinição inválido ou expirado"));
    }

    @Test
    void confirmAnswersBadRequestAndNeverUnauthorized() throws Exception {
        fixtures.tenant("acme");

        // 401 faria o interceptor do frontend tratar como "sessão expirada" e redirecionar um
        // visitante anônimo que nunca teve sessão.
        confirm("token-que-nao-existe", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void confirmOfAnInactiveAccountIsTheSameBadRequest() throws Exception {
        var company = fixtures.company("Acme", "acme");
        fixtures.inactiveUser(company, "inativo@acme.test", Role.ANALYST);
        String token = plantToken("inativo@acme.test", Instant.now().plusSeconds(600));

        confirm(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Link de redefinição inválido ou expirado"));
    }

    @Test
    void everyConfirmRejectionCarriesTheSameMessage() throws Exception {
        fixtures.tenant("acme");
        String expired = plantToken("admin@acme.test", Instant.now().minusSeconds(60));

        String unknown = errorOf(confirm("token-inexistente", NEW_PASSWORD));
        String stale = errorOf(confirm(expired, NEW_PASSWORD));

        assertThat(stale).isEqualTo(unknown);
    }

    @Test
    void confirmRevokesEveryRefreshTokenOfTheUser() throws Exception {
        fixtures.tenant("acme");
        String refreshToken = login("admin@acme.test", TestDataFactory.DEFAULT_PASSWORD, status().isOk())
                .get("refreshToken").asText();
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                SecretTokens.hash(refreshToken))).isEqualTo("PASSWORD_RESET");
    }

    @Test
    void confirmDoesNotTouchLastLoginAt() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());

        // Redefinir senha não é entrar: contaminar lastLoginAt mentiria sobre a última sessão.
        assertThat(userRepository.findByEmail("admin@acme.test").orElseThrow(AssertionError::new)
                .getLastLoginAt()).isNull();
    }

    @Test
    void confirmEnforcesTheSamePasswordLengthAsRegistration() throws Exception {
        fixtures.tenant("acme");
        String token = plantToken("admin@acme.test", Instant.now().plusSeconds(600));

        confirm(token, "curta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void requestValidatesTheAddress() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nao-e-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void theRequestIsAudited() throws Exception {
        fixtures.tenant("acme");

        requestReset("admin@acme.test").andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PASSWORD_RESET'", Long.class))
                .isEqualTo(1L);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Planta uma linha com o hash de um token escolhido pelo teste. É o mesmo caminho que o
     * serviço percorre na confirmação — ele só conhece o digest — sem depender do e-mail.
     */
    private String plantToken(String email, Instant expiresAt) {
        User user = userRepository.findByEmail(email).orElseThrow(AssertionError::new);
        String plaintext = SecretTokens.random();
        jdbcTemplate.update("INSERT INTO password_reset_tokens "
                        + "(user_id, token_hash, expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, now(), now())",
                user.getId(), SecretTokens.hash(plaintext), Timestamp.from(expiresAt));
        return plaintext;
    }

    private org.springframework.test.web.servlet.ResultActions requestReset(String email)
            throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        return mockMvc.perform(post("/api/v1/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password)
            throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("token", token);
        payload.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    private JsonNode login(String email, String password,
                           org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("password", password);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private String bodyOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String errorOf(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        JsonNode node = objectMapper.readTree(bodyOf(actions.andExpect(status().isBadRequest())));
        return node.get("status").asText() + "|" + node.get("code").asText() + "|"
                + node.get("message").asText();
    }

    private long tokenCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM password_reset_tokens", Long.class);
    }

    private String singleHash() {
        return jdbcTemplate.queryForObject("SELECT token_hash FROM password_reset_tokens", String.class);
    }

    private Long userIdOfSingleToken() {
        return jdbcTemplate.queryForObject("SELECT user_id FROM password_reset_tokens", Long.class);
    }
}
