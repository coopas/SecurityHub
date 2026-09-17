package com.securityhub.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securityhub.company.Company;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Covers everything a client could send instead of a legitimate token, and the
 * boundary between two tenants. These assertions are what prove the API does not rely on
 * the Angular guards.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_SECRET =
            "test-only-secret-value-used-exclusively-by-the-automated-test-suite";

    @Autowired
    private UserRepository userRepository;

    @Test
    void protectedEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Autenticação necessária"));
    }

    @Test
    void rejectsGarbageAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer nao-e-um-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsHeaderWithoutBearerPrefix() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.token(tenant.admin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String expired = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject(String.valueOf(tenant.admin.getId()))
                .claim("companyId", tenant.company.getId())
                .claim("role", "ADMIN")
                .setIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .setExpiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String forged = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject(String.valueOf(tenant.admin.getId()))
                .claim("companyId", tenant.company.getId())
                .claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(
                                "um-segredo-totalmente-diferente-com-tamanho-suficiente"
                                        .getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithTamperedPayload() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String[] parts = fixtures.token(tenant.viewer).split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AB." + parts[2];

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    /** A token whose role claim was swapped must not grant the privileges it names. */
    @Test
    void rejectsTokenWhoseRoleClaimDoesNotMatchTheStoredUser() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String escalated = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject(String.valueOf(tenant.viewer.getId()))
                .claim("companyId", tenant.company.getId())
                .claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + escalated))
                .andExpect(status().isUnauthorized());
    }

    /** Same idea for the company claim: it must agree with the persisted row. */
    @Test
    void rejectsTokenWhoseCompanyClaimDoesNotMatchTheStoredUser() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        Company other = fixtures.company("Globex", "globex");

        String crossed = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject(String.valueOf(acme.admin.getId()))
                .claim("companyId", other.getId())
                .claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + crossed))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenStopsWorkingAsSoonAsTheUserIsDeactivated() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String bearer = fixtures.bearer(tenant.analyst);

        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer))
                .andExpect(status().isOk());

        User analyst = userRepository.findById(tenant.analyst.getId()).orElseThrow(AssertionError::new);
        analyst.setActive(false);
        userRepository.saveAndFlush(analyst);

        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditTrailIsRestrictedToAdmin() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isOk());

        for (User denied : new User[]{tenant.analyst, tenant.developer, tenant.viewer}) {
            mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", fixtures.bearer(denied)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void userListingIsRestrictedToAdminAndAnalyst() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");

        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(tenant.admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(tenant.analyst)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(tenant.developer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(tenant.viewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void userListingNeverLeaksAnotherCompany() throws Exception {
        fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");

        mockMvc.perform(get("/api/v1/users").header("Authorization", fixtures.bearer(globex.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.email =~ /.*acme.*/)]").isEmpty())
                .andExpect(jsonPath("$[0].companyId").value(globex.company.getId()));
    }

    @Test
    void auditTrailNeverLeaksAnotherCompany() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        TestDataFactory.Tenant globex = fixtures.tenant("globex");

        // A real login writes one LOGIN row, owned by Acme.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@acme.test\",\"password\":\""
                                + TestDataFactory.DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", fixtures.bearer(globex.admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.actorId == " + acme.admin.getId() + ")]").isEmpty());
    }

    @Test
    void publicEndpointsStayReachableWithoutToken() throws Exception {
        // /actuator/health left this list: the management endpoints now live on their own port,
        // the one from management.server.port. What is left of this port is covered by
        // ObservabilityIntegrationTest.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void unknownProtectedPathStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/inexistente"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerOfAnotherCompanyCannotReadTheAuditTrail() throws Exception {
        TestDataFactory.Tenant acme = fixtures.tenant("acme");
        Company globex = fixtures.company("Globex", "globex");
        User globexViewer = fixtures.user(globex, "viewer@globex.test", Role.VIEWER);

        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", fixtures.bearer(globexViewer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-logs").header("Authorization", fixtures.bearer(acme.admin)))
                .andExpect(status().isOk());
    }
}
