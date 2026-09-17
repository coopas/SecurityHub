package com.securityhub.audit;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The audit trail is readable by every ADMIN of a company, so anything that could carry a
 * credential is replaced before it is serialised. Matching is done on the key name because
 * new DTO fields appear over time and a denylist of names fails closed for the shapes we
 * care about (password, hash, token, secret).
 */
public final class AuditSanitizer {

    static final String REDACTED = "***";

    private static final List<String> SENSITIVE_FRAGMENTS = Arrays.asList(
            "password", "senha", "passwordhash", "hash", "token", "secret", "credential",
            "authorization", "apikey", "api_key", "otp", "cvv");

    private AuditSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            result.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(String key, Object value) {
        if (isSensitive(key)) {
            return REDACTED;
        }
        if (value instanceof Map) {
            return sanitize((Map<String, Object>) value);
        }
        if (value instanceof List) {
            return ((List<Object>) value).stream()
                    .map(item -> sanitizeValue(key, item))
                    .collect(Collectors.toList());
        }
        return value;
    }

    private static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "");
        return SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }
}
