package com.securityhub.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditSanitizerTest {

    @Test
    void redactsSensitiveKeysRegardlessOfCaseAndUnderscores() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", "ana@acme.test");
        values.put("password", "senha-secreta");
        values.put("passwordHash", "$2a$12$abc");
        values.put("PASSWORD_HASH", "$2a$12$abc");
        values.put("accessToken", "eyJhbGci");
        values.put("apiKey", "sk-123");
        values.put("senha", "outra");

        Map<String, Object> sanitized = AuditSanitizer.sanitize(values);

        assertThat(sanitized.get("email")).isEqualTo("ana@acme.test");
        assertThat(sanitized).containsEntry("password", "***")
                .containsEntry("passwordHash", "***")
                .containsEntry("PASSWORD_HASH", "***")
                .containsEntry("accessToken", "***")
                .containsEntry("apiKey", "***")
                .containsEntry("senha", "***");
    }

    @Test
    void redactsInsideNestedMaps() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("password", "segredo");
        nested.put("name", "Ana");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("user", nested);

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedUser = (Map<String, Object>) AuditSanitizer.sanitize(values).get("user");

        assertThat(sanitizedUser).containsEntry("password", "***").containsEntry("name", "Ana");
    }

    @Test
    void redactsAWholeListHeldBySensitiveKey() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tokens", Arrays.asList("a", "b"));

        assertThat(AuditSanitizer.sanitize(values).get("tokens")).isEqualTo("***");
    }

    @Test
    void redactsSensitiveKeysInsideAListOfObjects() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "Ana");
        first.put("password", "segredo");

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("members", Arrays.asList(first));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) AuditSanitizer.sanitize(values).get("members");

        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("name", "Ana").containsEntry("password", "***");
    }

    @Test
    void keepsNonSensitiveValuesUntouched() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", "OPEN");
        values.put("cvssScore", 7.5);
        values.put("assignedTo", 42L);

        assertThat(AuditSanitizer.sanitize(values)).isEqualTo(values);
    }

    @Test
    void handlesNullInput() {
        assertThat(AuditSanitizer.sanitize(null)).isNull();
    }
}
