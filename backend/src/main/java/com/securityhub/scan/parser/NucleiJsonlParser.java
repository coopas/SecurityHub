package com.securityhub.scan.parser;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.securityhub.scan.ScanFinding;
import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import com.securityhub.vulnerability.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads nuclei's JSONL output: one JSON object per line, with no array wrapping them.
 *
 * <h2>Why the lines are not split by hand</h2>
 *
 * <p>Jackson iterates root-level values natively, so the stream is handed to a {@link
 * MappingIterator} instead of being cut on {@code \n}. Splitting on newlines looks equivalent
 * and is not: a template that captured a response body puts real newlines inside a JSON
 * string, and every hand-rolled splitter turns that one finding into two broken halves.
 *
 * <h2>Surviving a bad line</h2>
 *
 * <p>Tolerance starts once the stream reads as JSONL at all: a file whose very first bytes are
 * not JSON is the wrong file, and it is refused with a message saying so rather than imported
 * as zero findings, which looks like a clean scan.
 *
 * <p>After that, a line that does not parse is skipped and the iteration continues — nuclei running for an
 * hour and writing one truncated record must not cost the other four hundred. The loop stops
 * only when the parser stops advancing, which is what a truncated final object looks like:
 * without that check the same end-of-input error repeats forever.
 *
 * <h2>Severity</h2>
 *
 * <p>nuclei's own scale maps straight across, with {@code info} folded into {@link
 * Severity#LOW} because the contract has nothing below it, and {@code unknown} — or anything
 * unrecognised — treated as {@code MEDIUM} rather than as informational: a template that did
 * not state a severity has not said the finding is unimportant.
 */
@Slf4j
@Component
public class NucleiJsonlParser implements ScannerParser {

    /** Private, for the same reason as in {@code ZapJsonParser}: never touch the shared bean. */
    private static final ObjectReader READER = new ObjectMapper().readerFor(JsonNode.class);

    private static final String INFO = "info";
    private static final String CLASSIFICATION = "classification";

    @Override
    public ScanFormat format() {
        return ScanFormat.NUCLEI_JSONL;
    }

    @Override
    public List<ScanFinding> parse(InputStream input) {
        InputStream content = ScanNormalizer.requireContent(input, "nuclei");
        List<ScanFinding> findings = new ArrayList<>();
        try (MappingIterator<JsonNode> lines = READER.readValues(content)) {
            long progress = -1;
            while (true) {
                try {
                    if (!lines.hasNextValue()) {
                        break;
                    }
                    JsonNode line = lines.nextValue();
                    progress = offset(lines);
                    ScanFinding finding = toFinding(line);
                    if (finding != null) {
                        findings.add(finding);
                    }
                } catch (JsonProcessingException ex) {
                    long current = offset(lines);
                    log.debug("Linha do nuclei ignorada: {}", ex.getOriginalMessage());
                    if (current <= progress) {
                        break;
                    }
                    progress = current;
                }
            }
        } catch (IOException ex) {
            log.debug("Relatório nuclei ilegível: {}", ex.getMessage());
            throw new ScanParseException(
                    "O relatório nuclei enviado não está no formato JSONL esperado");
        }
        return findings;
    }

    private ScanFinding toFinding(JsonNode line) {
        JsonNode info = line.path(INFO);
        String title = ScanNormalizer.title(text(info, "name"));
        if (title == null) {
            return null;
        }
        JsonNode classification = info.path(CLASSIFICATION);
        String ruleId = trimToNull(text(line, "template-id"));
        String target = target(line);
        String cve = ScanNormalizer.cve(texts(classification.path("cve-id")));
        return new ScanFinding(
                ruleId,
                title,
                ScanNormalizer.description(text(info, "description")),
                severity(text(info, "severity")),
                ScanNormalizer.cvss(text(classification, "cvss-score")),
                cve,
                target,
                discoveredAt(text(line, "timestamp")),
                ScanNormalizer.fingerprint(ScanFormat.NUCLEI_JSONL, ruleId, target, cve));
    }

    /**
     * {@code matched-at} is the exact place the template fired and is what an asset identifier
     * is compared against; {@code host} is the fallback for the templates that report only one.
     */
    private static String target(JsonNode line) {
        String matchedAt = trimToNull(text(line, "matched-at"));
        return matchedAt != null ? matchedAt : trimToNull(text(line, "host"));
    }

    private static Severity severity(String raw) {
        if (raw == null) {
            return Severity.MEDIUM;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "info":
            case "low":
                return Severity.LOW;
            case "medium":
                return Severity.MEDIUM;
            case "high":
                return Severity.HIGH;
            case "critical":
                return Severity.CRITICAL;
            default:
                return Severity.MEDIUM;
        }
    }

    /** nuclei stamps every finding in RFC 3339; anything else falls back to the import instant. */
    private static Instant discoveredAt(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(timestamp.trim()).toInstant();
        } catch (DateTimeParseException ex) {
            return Instant.now();
        }
    }

    /** {@code cve-id} is an array in current nuclei and a bare string in older templates. */
    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode element : node) {
                values.add(element.asText());
            }
        } else if (!node.isMissingNode() && !node.isNull()) {
            values.add(node.asText());
        }
        return values;
    }

    private static long offset(MappingIterator<JsonNode> lines) {
        JsonLocation location = lines.getCurrentLocation();
        return location.getByteOffset() >= 0 ? location.getByteOffset() : location.getCharOffset();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
