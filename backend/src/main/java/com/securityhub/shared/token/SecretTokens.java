package com.securityhub.shared.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Single-use secrets handed out by e-mail or held by a client: refresh tokens, password-reset
 * links and invitation links. All three share the same shape (ADR 0006): the caller keeps the
 * plaintext, the database keeps only the digest.
 *
 * SHA-256 and not BCrypt on purpose. The input is 256 bits from a CSPRNG rather than a human
 * password, so the slow hash defends against nothing, and a BCrypt digest cannot be looked up
 * by equality — finding the row behind a presented token would mean comparing it against every
 * row of the table.
 */
public final class SecretTokens {

    /** 32 bytes: the same entropy as the HMAC key, and well past any birthday concern. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SecretTokens() {
    }

    /**
     * Base64-URL without padding so the value survives a query string untouched: the
     * invitation and reset links carry it as {@code ?token=...}.
     */
    public static String random() {
        byte[] material = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /**
     * Lowercase hex, 64 characters — the exact shape the {@code ^[0-9a-f]{64}$} CHECK of V7
     * enforces on every column that stores one of these digests.
     */
    public static String hash(String token) {
        if (token == null) {
            throw new IllegalArgumentException("Token nulo não pode ser derivado");
        }
        byte[] digest = digest(token.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return hex.toString();
    }

    private static byte[] digest(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException ex) {
            // Every JRE ships SHA-256; this branch exists only because the API is checked.
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", ex);
        }
    }
}
