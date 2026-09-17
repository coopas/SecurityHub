package com.securityhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityhub.shared.token.SecretTokens;
import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A sweep: the audit trail is readable by any ADMIN of the company, so a secret that lands in it
 * stops being a secret for all of them at once.
 *
 * The test exercises every flow that handles sensitive material, keeps each plaintext value that
 * passed through its hands and then sweeps the value columns of the audit rows. Beyond the exact
 * values, it looks for the shapes: the prefix of a BCrypt hash, the header of a JWT, the
 * hexadecimal digest stored in the database and any long Base64-URL sequence, which is what an
 * opaque token would look like even if this test did not know its value.
 */
class TokenMaterialNeverLeaksIntegrationTest extends AbstractIntegrationTest {

    /** An opaque token has 43 characters; names, e-mails and dates form no such sequences. */
    private static final Pattern OPAQUE_TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{40,}");

    private static final String PASSWORD = "senha-de-teste-123";
    private static final String NEW_PASSWORD = "senha-nova-do-usuario";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noTokenMaterialEverReachesTheAuditTrail() throws Exception {
        List<String> secrets = new ArrayList<>();
        secrets.add(PASSWORD);
        secrets.add(NEW_PASSWORD);

        // 1. Registration: creates the company, the administrator and the first session.
        JsonNode registered = json(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("companyName", "Acme", "name", "Administrador",
                        "email", "admin@acme.test", "password", PASSWORD)), status().isCreated());
        collectSession(secrets, registered);
        Long adminId = registered.get("user").get("id").asLong();
        String adminBearer = "Bearer " + registered.get("accessToken").asText();

        // 2. Login and rotation.
        JsonNode loggedIn = json(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("email", "admin@acme.test", "password", PASSWORD)), status().isOk());
        collectSession(secrets, loggedIn);
        JsonNode rotated = json(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("refreshToken", loggedIn.get("refreshToken").asText())),
                status().isOk());
        collectSession(secrets, rotated);

        // 3. Reusing the already rotated token, which is the path that audits TOKEN_REUSE_DETECTED.
        ageUsedAt(loggedIn.get("refreshToken").asText());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("refreshToken", loggedIn.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized());

        // 4. Logout of a fresh session.
        JsonNode toLogout = json(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("email", "admin@acme.test", "password", PASSWORD)), status().isOk());
        collectSession(secrets, toLogout);
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("refreshToken", toLogout.get("refreshToken").asText())))
                .andExpect(status().isNoContent());

        // 5. Password reset, request and confirmation.
        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("email", "admin@acme.test")))
                .andExpect(status().isAccepted());
        String resetToken = replantResetToken(adminId);
        secrets.add(resetToken);
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("token", resetToken, "password", NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        // 6. Invitation issued, revoked, reissued and accepted.
        JsonNode firstInvite = json(post("/api/v1/invitations").header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("name", "Bruno", "email", "bruno@acme.test", "role", "ANALYST")),
                status().isCreated());
        mockMvc.perform(delete("/api/v1/invitations/" + firstInvite.get("id").asLong())
                .header("Authorization", adminBearer)).andExpect(status().isNoContent());
        JsonNode invite = json(post("/api/v1/invitations").header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("name", "Bruno", "email", "bruno@acme.test", "role", "ANALYST")),
                status().isCreated());
        String inviteToken = replantInvitationToken(invite.get("id").asLong());
        secrets.add(inviteToken);
        JsonNode accepted = json(post("/api/v1/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("token", inviteToken, "password", PASSWORD)), status().isCreated());
        collectSession(secrets, accepted);

        // 7. Management of the newly created user: name, role and deactivation.
        Long brunoId = accepted.get("user").get("id").asLong();
        mockMvc.perform(patch("/api/v1/users/" + brunoId).header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(payload("name", "Bruno Souza")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/" + brunoId + "/role").header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(payload("role", "VIEWER")))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/" + brunoId + "/active").header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        // Sanity: the sweep is only worth anything if there was something to sweep.
        List<String> values = auditValues();
        assertThat(values).isNotEmpty();
        assertThat(secrets).hasSizeGreaterThan(8);

        for (String value : values) {
            for (String secret : secrets) {
                assertThat(value)
                        .as("valor de auditoria não pode conter material de token")
                        .doesNotContain(secret);
            }
            for (String storedHash : storedHashes()) {
                assertThat(value).doesNotContain(storedHash);
            }
            assertThat(value).doesNotContain("$2");
            assertThat(value).doesNotContain("eyJ");
            assertThat(OPAQUE_TOKEN_SHAPE.matcher(value).find())
                    .as("nenhum valor de auditoria deve parecer um token opaco: %s", value)
                    .isFalse();
        }
    }

    @Test
    void theSweepWouldCatchALeak() throws Exception {
        TestDataFactory.Tenant tenant = fixtures.tenant("acme");
        String token = SecretTokens.random();
        // Proof that the three shapes looked for above really do fire; without this the sweep
        // could be passing because it finds nothing anywhere at all.
        jdbcTemplate.update("INSERT INTO audit_logs (company_id, actor_id, actor_email, action, "
                        + "entity_type, entity_id, new_value_json, created_at) "
                        + "VALUES (?, ?, ?, 'UPDATE', 'User', ?, ?, now())",
                tenant.company.getId(), tenant.admin.getId(), tenant.admin.getEmail(),
                tenant.admin.getId(), "{\"leak\":\"" + token + "\"}");

        List<String> values = auditValues();

        assertThat(values).anyMatch(value -> value.contains(token));
        assertThat(values).anyMatch(value -> OPAQUE_TOKEN_SHAPE.matcher(value).find());
    }

    // --- helpers -------------------------------------------------------------

    private void collectSession(List<String> secrets, JsonNode response) {
        secrets.add(response.get("accessToken").asText());
        secrets.add(response.get("refreshToken").asText());
    }

    private List<String> auditValues() {
        return jdbcTemplate.queryForList(
                "SELECT coalesce(old_value_json, '') || ' ' || coalesce(new_value_json, '') "
                        + "FROM audit_logs", String.class);
    }

    private List<String> storedHashes() {
        List<String> hashes = new ArrayList<>(
                jdbcTemplate.queryForList("SELECT token_hash FROM refresh_tokens", String.class));
        hashes.addAll(jdbcTemplate.queryForList("SELECT token_hash FROM invitations", String.class));
        hashes.addAll(jdbcTemplate.queryForList("SELECT password_hash FROM users", String.class));
        return hashes;
    }

    private String replantResetToken(Long userId) {
        String plaintext = SecretTokens.random();
        jdbcTemplate.update("UPDATE password_reset_tokens SET token_hash = ? WHERE user_id = ?",
                SecretTokens.hash(plaintext), userId);
        return plaintext;
    }

    private String replantInvitationToken(Long invitationId) {
        String plaintext = SecretTokens.random();
        jdbcTemplate.update("UPDATE invitations SET token_hash = ? WHERE id = ?",
                SecretTokens.hash(plaintext), invitationId);
        return plaintext;
    }

    private void ageUsedAt(String token) {
        jdbcTemplate.update("UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(120)), SecretTokens.hash(token));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                                  builder,
                          org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        String response = mockMvc.perform(builder).andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private String payload(String... keysAndValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return json(values);
    }
}
