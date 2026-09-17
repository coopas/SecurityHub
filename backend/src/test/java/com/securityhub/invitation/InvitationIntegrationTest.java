package com.securityhub.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.company.Company;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class InvitationIntegrationTest extends AbstractIntegrationTest {

    private static final String ACCEPT_PASSWORD = "senha-do-convidado-1";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Test
    void adminInvitesAndTheResponseNeverCarriesTheToken() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        String response = invite(tenant.admin, "novo@acme.test", Role.ANALYST)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("novo@acme.test"))
                .andExpect(jsonPath("$.role").value("ANALYST"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.invitedByName").value(tenant.admin.getName()))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.tokenHash").doesNotExist())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String storedHash = jdbcTemplate.queryForObject("SELECT token_hash FROM invitations",
                String.class);
        assertThat(storedHash).matches("^[0-9a-f]{64}$");
        assertThat(response).doesNotContain(storedHash);
    }

    @Test
    void theInvitedAddressIsNormalized() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        invite(tenant.admin, "MAIUSCULO@ACME.TEST", Role.VIEWER).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT email FROM invitations", String.class))
                .isEqualTo("maiusculo@acme.test");
    }

    @Test
    void aNonAdminCannotInvite() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        invite(tenant.analyst, "novo@acme.test", Role.VIEWER).andExpect(status().isForbidden());
        invite(tenant.viewer, "outro@acme.test", Role.VIEWER).andExpect(status().isForbidden());
    }

    @Test
    void invitingAnAddressThatAlreadyHasAnAccountConflicts() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        invite(tenant.admin, "analyst@acme.test", Role.VIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("E-mail já cadastrado"));
    }

    @Test
    void invitingAnAddressThatHasAnAccountInAnotherCompanyRevealsNothingExtra() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        fixtures.tenant("globex");

        invite(acme.admin, "analyst@globex.test", Role.VIEWER)
                .andExpect(status().isConflict())
                // A mesma mensagem do cadastro: a resposta não diz em que empresa o endereço está.
                .andExpect(jsonPath("$.message").value("E-mail já cadastrado"));
    }

    @Test
    void invitingAnAddressWithALiveInvitationInAnotherCompanyConflicts() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        invite(globex.admin, "disputado@exemplo.test", Role.ANALYST).andExpect(status().isCreated());

        // Sem esta barreira, o perdedor da corrida descobriria o problema só no aceite, na
        // forma de uma violação crua da unicidade global de users.email.
        invite(acme.admin, "disputado@exemplo.test", Role.VIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("E-mail já cadastrado"));
    }

    @Test
    void reInvitingTheSameAddressReplacesThePendingRow() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        invite(tenant.admin, "novo@acme.test", Role.VIEWER).andExpect(status().isCreated());

        invite(tenant.admin, "novo@acme.test", Role.ANALYST).andExpect(status().isCreated());

        assertThat(countByStatus("PENDING")).isEqualTo(1L);
        assertThat(countByStatus("REVOKED")).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM invitations WHERE status = 'PENDING'", String.class))
                .isEqualTo("ANALYST");
    }

    @Test
    void theListingIsABareArrayScopedToTheCompany() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        invite(acme.admin, "um@acme.test", Role.VIEWER);
        invite(globex.admin, "outro@globex.test", Role.VIEWER);

        mockMvc.perform(get("/api/v1/invitations").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("um@acme.test"));
    }

    @Test
    void revokeMarksThePendingInvitation() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));

        mockMvc.perform(delete("/api/v1/invitations/" + id)
                        .header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isNoContent());

        assertThat(countByStatus("REVOKED")).isEqualTo(1L);
    }

    @Test
    void anInvitationOfAnotherCompanyIsNotFoundAndNeverForbidden() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");
        Long id = idOf(invite(globex.admin, "novo@globex.test", Role.VIEWER));

        mockMvc.perform(delete("/api/v1/invitations/" + id)
                        .header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void revokingAnAlreadyAcceptedInvitationConflicts() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));
        accept(token).andExpect(status().isCreated());

        // Rebaixar ACCEPTED para REVOKED violaria a equivalência com accepted_at de V7.
        mockMvc.perform(delete("/api/v1/invitations/" + id)
                        .header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void thePreviewShowsWhereTheInviteLeadsTo() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.ANALYST));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));

        mockMvc.perform(get("/api/v1/invitations/accept").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("novo@acme.test"))
                .andExpect(jsonPath("$.companyName").value("ACME"))
                .andExpect(jsonPath("$.role").value("ANALYST"))
                .andExpect(jsonPath("$.name").isNotEmpty());
    }

    @Test
    void thePreviewOfAnUnknownTokenIsABadRequest() throws Exception {
        fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/invitations/accept").param("token", "nao-existe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Convite inválido ou expirado"));
    }

    @Test
    void acceptCreatesTheAccountAndReturnsASession() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.DEVELOPER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));

        accept(token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("novo@acme.test"))
                .andExpect(jsonPath("$.user.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.user.companyId").value(tenant.company.getId()));

        User created = userRepository.findByEmail("novo@acme.test").orElseThrow(AssertionError::new);
        assertThat(created.getPasswordHash()).startsWith("$2");
        assertThat(created.isActive()).isTrue();
        assertThat(countByStatus("ACCEPTED")).isEqualTo(1L);
    }

    @Test
    void theAcceptedAccountCanLogInWithTheChosenPassword() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));
        accept(token).andExpect(status().isCreated());

        Map<String, String> payload = new HashMap<>();
        payload.put("email", "novo@acme.test");
        payload.put("password", ACCEPT_PASSWORD);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isOk());
    }

    @Test
    void acceptIsSingleUse() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));
        accept(token).andExpect(status().isCreated());

        accept(token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Convite inválido ou expirado"));
        assertThat(userRepository.findByEmail("novo@acme.test")).isPresent();
    }

    @Test
    void acceptOfARevokedInvitationIsTheSameBadRequest() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));
        mockMvc.perform(delete("/api/v1/invitations/" + id)
                .header("Authorization", fixtures.bearer(tenant.admin)));

        accept(token).andExpect(status().isBadRequest());
        assertThat(userRepository.findByEmail("novo@acme.test")).isNotPresent();
    }

    @Test
    void acceptOfAnExpiredInvitationIsTheSameBadRequest() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().minusSeconds(60));

        accept(token).andExpect(status().isBadRequest());
    }

    @Test
    void acceptValidatesThePasswordLikeRegistration() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        Long id = idOf(invite(tenant.admin, "novo@acme.test", Role.VIEWER));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));

        Map<String, String> payload = new HashMap<>();
        payload.put("token", token);
        payload.put("password", "curta");
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON).content(json(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * O teste que justifica a tabela separada. Se o convite pendente fosse uma linha de users
     * inativa — ou se contasse de qualquer outra forma — o único administrador de verdade
     * conseguiria se rebaixar e a empresa ficaria sem ninguém capaz de administrá-la, esperando
     * por um aceite que pode nunca acontecer.
     */
    @Test
    void aPendingAdminInvitationDoesNotCountAsAnActiveAdmin() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        User admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        fixtures.user(company, "analyst@acme.test", Role.ANALYST);
        invite(admin, "futuro-admin@acme.test", Role.ADMIN).andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/users/" + admin.getId() + "/role")
                        .header("Authorization", fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("A empresa precisa de ao menos um administrador ativo"));

        // E o convite continua pendente: a recusa não mexeu nele.
        assertThat(countByStatus("PENDING")).isEqualTo(1L);
        assertThat(userRepository.findById(admin.getId()).orElseThrow(AssertionError::new).getRole())
                .isEqualTo(Role.ADMIN);
    }

    @Test
    void acceptingTheAdminInvitationIsWhatFreesTheDemotion() throws Exception {
        Company company = fixtures.company("Acme", "acme");
        User admin = fixtures.user(company, "admin@acme.test", Role.ADMIN);
        Long id = idOf(invite(admin, "futuro-admin@acme.test", Role.ADMIN));
        String token = replantToken(id, Instant.now().plus(7, ChronoUnit.DAYS));
        accept(token).andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/users/" + admin.getId() + "/role")
                        .header("Authorization", fixtures.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void theAcceptEndpointsNeedNoAuthentication() throws Exception {
        fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/invitations/accept").param("token", "qualquer"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"qualquer\",\"password\":\"senha-longa-o-bastante\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invitingIsAudited() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        invite(tenant.admin, "novo@acme.test", Role.ANALYST).andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE entity_type = 'Invitation'", Long.class))
                .isEqualTo(1L);
    }

    // --- helpers -------------------------------------------------------------

    private ResultActions invite(User actor, String email, Role role) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", email.split("@")[0]);
        payload.put("email", email);
        payload.put("role", role.name());
        return mockMvc.perform(post("/api/v1/invitations")
                .header("Authorization", fixtures.bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    private ResultActions accept(String token) throws Exception {
        Map<String, String> payload = new HashMap<>();
        payload.put("token", token);
        payload.put("password", ACCEPT_PASSWORD);
        return mockMvc.perform(post("/api/v1/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON).content(json(payload)));
    }

    /**
     * O token em claro sai só pelo e-mail, e a suíte não espera pela thread assíncrona
     * (ADR 0007). Trocar o hash da linha por um de token conhecido exercita exatamente a
     * consulta que o aceite faz, e ainda permite escolher o vencimento.
     */
    private String replantToken(Long invitationId, Instant expiresAt) {
        String plaintext = SecretTokens.random();
        jdbcTemplate.update("UPDATE invitations SET token_hash = ?, expires_at = ? WHERE id = ?",
                SecretTokens.hash(plaintext), Timestamp.from(expiresAt), invitationId);
        return plaintext;
    }

    private Long idOf(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("id").asLong();
    }

    private long countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM invitations WHERE status = ?",
                Long.class, status);
    }
}
