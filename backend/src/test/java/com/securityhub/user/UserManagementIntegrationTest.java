package com.securityhub.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.company.Company;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class UserManagementIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    void adminReadsAUserOfItsOwnCompany() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/users/" + tenant.analyst.getId())
                        .header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("analyst@acme.test"))
                .andExpect(jsonPath("$.role").value("ANALYST"));
    }

    @Test
    void aUserOfAnotherCompanyIsNotFoundAndNeverForbidden() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");

        // 404 antes de qualquer regra de posse: um 403 confirmaria que o id existe.
        mockMvc.perform(get("/api/v1/users/" + globex.analyst.getId())
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/users/" + globex.analyst.getId() + "/role")
                        .header("Authorization", fixtures.bearer(acme.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonAdminCannotManageUsers() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/users/" + tenant.viewer.getId())
                        .header("Authorization", fixtures.bearer(tenant.analyst)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/users/" + tenant.viewer.getId())
                        .header("Authorization", fixtures.bearer(tenant.analyst))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Novo Nome\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchUpdatesTheNameOnly() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId())
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Ana Silva  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ana Silva"))
                .andExpect(jsonPath("$.email").value("analyst@acme.test"));
    }

    @Test
    void thereIsNoWayToChangeAnEmail() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        // Um campo extra no corpo é simplesmente ignorado: o DTO não o declara, e é essa
        // ausência — não uma regra no serviço — que fecha a primitiva de tomada de conta.
        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId())
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"atacante@evil.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("analyst@acme.test"));

        assertThat(userRepository.findByEmail("atacante@evil.test")).isNotPresent();
        assertThat(userRepository.findById(tenant.analyst.getId()).orElseThrow(AssertionError::new)
                .getEmail()).isEqualTo("analyst@acme.test");
    }

    @Test
    void aRoleChangeTakesEffectOnTheVeryNextRequest() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String analystBearer = fixtures.bearer(tenant.analyst);
        // Antes: ANALYST consegue listar usuários.
        mockMvc.perform(get("/api/v1/users").header("Authorization", analystBearer))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId() + "/role")
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // O access token antigo carrega o papel antigo e o filtro o recusa contra a linha nova:
        // a permissão não sobrevive até o vencimento do token.
        mockMvc.perform(get("/api/v1/users").header("Authorization", analystBearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demotingTheLastActiveAdminConflicts() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        var admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        fixtures.user(company, "analyst@acme.test", Role.ANALYST);

        changeRole(admin.getId(), admin, "ANALYST")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A empresa precisa de ao menos um administrador ativo"));
    }

    @Test
    void deactivatingTheLastActiveAdminConflicts() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        var admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        var other = fixtures.user(company, "outro@acme.test", Role.ADMIN);
        // Sobra um só administrador ativo.
        changeActive(other.getId(), admin, false).andExpect(status().isOk());

        changeActive(admin.getId(), admin, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A empresa precisa de ao menos um administrador ativo"));
    }

    @Test
    void selfDeactivationIsRefusedEvenWithAnotherActiveAdmin() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        var admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        fixtures.user(company, "outro@acme.test", Role.ADMIN);

        changeActive(admin.getId(), admin, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Você não pode desativar a própria conta"));
        assertThat(userRepository.findById(admin.getId()).orElseThrow(AssertionError::new).isActive())
                .isTrue();
    }

    @Test
    void selfDemotionIsAllowedWhenAnotherActiveAdminRemains() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        var admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        fixtures.user(company, "outro@acme.test", Role.ADMIN);

        changeRole(admin.getId(), admin, "VIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void aRoleChangeRevokesEveryRefreshTokenOfTheUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String refreshToken = loginRefreshToken("analyst@acme.test");

        changeRole(tenant.analyst.getId(), tenant.admin, "VIEWER").andExpect(status().isOk());

        assertThat(revocationReasonOf(refreshToken)).isEqualTo("ROLE_CHANGED");
    }

    @Test
    void deactivationRevokesEveryRefreshTokenOfTheUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String refreshToken = loginRefreshToken("analyst@acme.test");

        changeActive(tenant.analyst.getId(), tenant.admin, false).andExpect(status().isOk());

        assertThat(revocationReasonOf(refreshToken)).isEqualTo("USER_DEACTIVATED");
    }

    @Test
    void reactivationRevokesNothing() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        changeActive(tenant.analyst.getId(), tenant.admin, false).andExpect(status().isOk());
        changeActive(tenant.analyst.getId(), tenant.admin, true).andExpect(status().isOk());
        String refreshToken = loginRefreshToken("analyst@acme.test");

        // Reativar não encerra sessão nenhuma: as que existiam já morreram na desativação, e a
        // que nasce depois é legítima.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM refresh_tokens WHERE token_hash = ?", String.class,
                SecretTokens.hash(refreshToken))).isEqualTo("ACTIVE");
    }

    @Test
    void aNoOpRoleChangeWritesNothing() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String refreshToken = loginRefreshToken("analyst@acme.test");
        long audits = auditCount();

        changeRole(tenant.analyst.getId(), tenant.admin, "ANALYST").andExpect(status().isOk());

        assertThat(auditCount()).isEqualTo(audits);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM refresh_tokens WHERE token_hash = ?", String.class,
                SecretTokens.hash(refreshToken))).isEqualTo("ACTIVE");
    }

    @Test
    void theAuditCarriesTheRealBeforeAndAfter() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        changeRole(tenant.analyst.getId(), tenant.admin, "DEVELOPER").andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT old_value_json, new_value_json FROM audit_logs WHERE action = 'USER_UPDATED'");
        assertThat((String) row.get("old_value_json")).contains("ANALYST");
        assertThat((String) row.get("new_value_json")).contains("DEVELOPER");
    }

    @Test
    void theListingStaysABareArray() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        // Paginar quebraria dois serviços do frontend que fazem cast direto para User[], e só em
        // tempo de execução.
        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void theActiveFlagIsRequiredInThePayload() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        // Com um boolean primitivo, um corpo vazio desativaria alguém em silêncio.
        mockMvc.perform(patch("/api/v1/users/" + tenant.analyst.getId() + "/active")
                        .header("Authorization", fixtures.bearer(tenant.admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(userRepository.findById(tenant.analyst.getId()).orElseThrow(AssertionError::new)
                .isActive()).isTrue();
    }

    // --- helpers -------------------------------------------------------------

    private ResultActions changeRole(Long id, User actor, String role) throws Exception {
        return mockMvc.perform(patch("/api/v1/users/" + id + "/role")
                .header("Authorization", fixtures.bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"));
    }

    private ResultActions changeActive(Long id, User actor, boolean active) throws Exception {
        return mockMvc.perform(patch("/api/v1/users/" + id + "/active")
                .header("Authorization", fixtures.bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}"));
    }

    private String loginRefreshToken(String email) throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("password", TestDataFactory.DEFAULT_PASSWORD);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).get("refreshToken").asText();
    }

    private String revocationReasonOf(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_reason FROM refresh_tokens WHERE token_hash = ?", String.class,
                SecretTokens.hash(refreshToken));
    }

    private long auditCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
    }
}
