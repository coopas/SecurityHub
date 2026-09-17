package com.securityhub.company;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

public final class SlugGenerator {

    private static final int MAX_LENGTH = 130;

    private SlugGenerator() {
    }

    public static String slugify(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.length() > MAX_LENGTH) {
            normalized = normalized.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return normalized.isEmpty() ? "empresa" : normalized;
    }

    /**
     * Appends -2, -3, ... until the slug is free. The caller supplies the existence check so
     * this stays usable from tests without a database.
     */
    public static String uniqueSlug(String value, Predicate<String> exists) {
        String base = slugify(value);
        if (!exists.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Não foi possível gerar um slug único para " + value);
    }
}
