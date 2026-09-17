package com.securityhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.company.CompanyRepository;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Test
    void registerCreatesCompanyWithAdminAndReturnsToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registerPayload("Acme Segurança", "admin@acme.test"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.email").value("admin@acme.test"))
                .andExpect(jsonPath("$.user.companyName").value("Acme Segurança"));

        User created = userRepository.findByEmail("admin@acme.test").orElseThrow(AssertionError::new);
        assertThat(created.getPasswordHash()).startsWith("$2");
        // getId() reads the proxy identifier; loading the row keeps the assertion outside
        // the persistence context, where open-in-view is disabled.
        assertThat(companyRepository.findById(created.getCompany().getId())
                .orElseThrow(AssertionError::new).getSlug()).isEqualTo("acme-seguranca");
    }

    @Test
    void registerNeverEchoesThePassword() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registerPayload("Acme", "admin@acme.test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("senha-de-teste-123").doesNotContain("passwordHash");
    }

    @Test
    void registerRejectsDuplicatedEmailWithConflict() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(registerPayload("Acme", "admin@acme.test"))));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registerPayload("Outra", "admin@acme.test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void registerValidatesPayload() throws Exception {
        Map<String, String> invalid = new HashMap<>();
        invalid.put("companyName", "");
        invalid.put("name", "A");
        invalid.put("email", "nao-e-email");
        invalid.put("password", "curta");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void loginReturnsTokenAndRecordsLastLogin() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginPayload("admin@acme.test", TestDataFactory.DEFAULT_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.id").value(tenant.admin.getId()));

        assertThat(userRepository.findByEmail("admin@acme.test").orElseThrow(AssertionError::new)
                .getLastLoginAt()).isNotNull();
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        fixtures.tenant("acme");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginPayload("ADMIN@ACME.TEST", TestDataFactory.DEFAULT_PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithWrongPasswordReturnsGenericUnauthorized() throws Exception {
        fixtures.tenant("acme");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginPayload("admin@acme.test", "senha-errada-qualquer"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Credenciais inválidas"));
    }

    @Test
    void loginWithUnknownEmailReturnsTheSameMessage() throws Exception {
        fixtures.tenant("acme");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginPayload("ninguem@acme.test", "senha-errada-qualquer"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Credenciais inválidas"));
    }

    @Test
    void inactiveUserCannotLogIn() throws Exception {
        var company = fixtures.company("Acme", "acme");
        fixtures.inactiveUser(company, "desativado@acme.test", Role.ANALYST);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(loginPayload("desativado@acme.test", TestDataFactory.DEFAULT_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", fixtures.bearer(tenant.analyst)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("analyst@acme.test"))
                .andExpect(jsonPath("$.role").value("ANALYST"))
                .andExpect(jsonPath("$.companyId").value(tenant.company.getId()));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private Map<String, String> registerPayload(String companyName, String email) {
        Map<String, String> payload = new HashMap<>();
        payload.put("companyName", companyName);
        payload.put("name", "Administrador");
        payload.put("email", email);
        payload.put("password", TestDataFactory.DEFAULT_PASSWORD);
        return payload;
    }

    private Map<String, String> loginPayload(String email, String password) {
        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("password", password);
        return payload;
    }
}
