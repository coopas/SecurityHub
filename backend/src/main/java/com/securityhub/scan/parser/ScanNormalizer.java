package com.securityhub.scan.parser;

import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import com.securityhub.vulnerability.VulnerabilityMapper;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * The single place where scanner output is made legal for the database.
 *
 * <p>All three parsers go through here, and they have to: the import service writes entities
 * straight to the repository, so Bean Validation never runs on an imported row. Whatever
 * leaves a parser is what reaches PostgreSQL. A CVSS of {@code 7.53} against a
 * {@code NUMERIC(3,1)} column, or a CVE-shaped string that the CHECK of V5 rejects, does not
 * fail as a validation error with a field name on it — it fails as a
 * {@code DataIntegrityViolationException}, which the global handler answers with a 409 about
 * a conflict, and the person reading that 409 has no way to reach the truth from it.
 *
 * <p>The rule throughout is the same: a value that cannot be made legal becomes {@code null},
 * never an exception and never a rejected row. Losing the CVSS of one finding is a smaller
 * loss than losing the finding.
 */
final class ScanNormalizer {

    /** Mirrors {@code vulnerabilities.title VARCHAR(200) NOT NULL}. */
    private static final int MAX_TITLE = 200;

    /** Mirrors {@code vulnerabilities.description VARCHAR(4000)}. */
    private static final int MAX_DESCRIPTION = 4000;

    /** Mirrors {@code vulnerabilities.cve VARCHAR(20)} and its CHECK. */
    private static final int MAX_CVE = 20;

    private static final Pattern CVE_FORMAT = Pattern.compile("^CVE-\\d{4}-\\d{4,}$");

    /**
     * Splits a free-text field into CVE candidates. Anything that is not a letter, a digit or
     * a hyphen separates: that covers ZAP's {@code "CVE-2021-44228, CVE-2021-45046"} and an
     * identifier sitting inside a sentence of nmap script output alike.
     */
    private static final Pattern CVE_SEPARATOR = Pattern.compile("[^A-Za-z0-9-]+");

    private static final BigDecimal MIN_CVSS = BigDecimal.ZERO;
    private static final BigDecimal MAX_CVSS = BigDecimal.TEN;

    /** Leading whitespace tolerated while deciding whether a report has any content. */
    private static final int BLANK_PROBE_BYTES = 8192;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ScanNormalizer() {
    }

    /**
     * Rejects an empty report before the real parser turns it into a confusing syntax error.
     *
     * <p>Returns a buffered stream positioned back at the first byte, so the caller parses the
     * whole document and not what is left after the probe. Only the first {@value
     * #BLANK_PROBE_BYTES} bytes are inspected; a report whose content starts later than that
     * is not a report anyone wrote.
     */
    static InputStream requireContent(InputStream input, String what) {
        if (input == null) {
            throw blank(what);
        }
        BufferedInputStream buffered = new BufferedInputStream(input, BLANK_PROBE_BYTES);
        buffered.mark(BLANK_PROBE_BYTES);
        try {
            for (int read = 0; read < BLANK_PROBE_BYTES; read++) {
                int current = buffered.read();
                if (current < 0) {
                    break;
                }
                if (!Character.isWhitespace(current)) {
                    buffered.reset();
                    return buffered;
                }
            }
            throw blank(what);
        } catch (IOException ex) {
            throw new ScanParseException("Não foi possível ler o relatório " + what + " enviado");
        }
    }

    private static ScanParseException blank(String what) {
        return new ScanParseException(
                "O relatório " + what + " enviado está vazio; envie o arquivo gerado pelo scanner");
    }

    /** Trimmed and cut to 200; {@code null} for anything blank, which the caller must skip. */
    static String title(String raw) {
        String trimmed = VulnerabilityMapper.normalizeTitle(raw);
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        return truncate(trimmed, MAX_TITLE);
    }

    static String description(String raw) {
        String trimmed = VulnerabilityMapper.normalizeDescription(raw);
        return trimmed == null ? null : truncate(trimmed, MAX_DESCRIPTION);
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * One decimal, HALF_UP, inside 0.0..10.0 — {@code 7.53} becomes {@code 7.5}.
     *
     * <p>Rounding happens before the range check, so a scanner that reports {@code 10.04}
     * keeps a perfectly usable {@code 10.0} instead of losing its score to a rounding artefact
     * of its own. Anything still outside the range afterwards, or anything unparseable, is
     * {@code null}: a CVSS of 11 says the scanner is not talking about CVSS, and inventing a
     * clamp to 10.0 would quietly promote it to the worst score there is.
     */
    static BigDecimal cvss(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        BigDecimal scaled = parsed.setScale(1, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_CVSS) < 0 || scaled.compareTo(MAX_CVSS) > 0) {
            return null;
        }
        return scaled;
    }

    /**
     * The first well-formed CVE in the text, or {@code null}.
     *
     * <p>First-match and not all-matches because the contract holds one CVE per finding, and
     * first is the only choice that does not depend on how a given scanner orders its list.
     * The length guard is not redundant with the pattern: {@code CVE-2021-1234567890123} is a
     * legal shape that does not fit {@code VARCHAR(20)}, and it is the column that decides.
     */
    static String cve(String raw) {
        if (raw == null) {
            return null;
        }
        for (String candidate : CVE_SEPARATOR.split(raw)) {
            String normalized = VulnerabilityMapper.normalizeCve(candidate);
            if (normalized != null && normalized.length() <= MAX_CVE
                    && CVE_FORMAT.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return null;
    }

    /** Same rule over a list, for the scanners that emit an array instead of a string. */
    static String cve(Iterable<String> candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String found = cve(candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * {@code sha256hex(scanner + ':' + ruleId + ':' + target + ':' + cve)}, lowercase.
     *
     * <p>What is in it is what makes the finding the same finding on the next scan: which
     * scanner said it, which rule fired, where, and about which CVE. A {@code null} component
     * contributes nothing but keeps its separator, so an absent CVE stays distinguishable
     * from a CVE that is literally the empty string.
     *
     * <p>Severity and CVSS are excluded deliberately. Scanner vendors re-score their own rules
     * between releases — a template that said {@code medium} last month says {@code high}
     * today for the same bug on the same host. Including them would make every re-scan after
     * such a change import a second copy of a vulnerability someone is already working on,
     * and the first copy would never close.
     */
    static String fingerprint(ScanFormat scanner, String ruleId, String target, String cve) {
        String material = String.join(":",
                nullToEmpty(scanner == null ? null : scanner.name()),
                nullToEmpty(ruleId),
                nullToEmpty(target),
                nullToEmpty(cve));
        return hex(digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static byte[] digest(byte[] material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (NoSuchAlgorithmException ex) {
            // Every JRE ships SHA-256; this branch exists only because the API is checked.
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", ex);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            result.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return result.toString();
    }
}
