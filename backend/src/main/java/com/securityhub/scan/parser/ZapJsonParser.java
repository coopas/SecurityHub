package com.securityhub.scan.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.securityhub.scan.ScanFinding;
import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import com.securityhub.vulnerability.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads the OWASP ZAP traditional JSON report: {@code site[] -> alerts[] -> instances[]}.
 *
 * <h2>One finding per instance</h2>
 *
 * <p>ZAP reports an alert once and lists every URI it fired on underneath it. The import
 * fans that back out: an alert with three instances becomes three findings sharing a
 * {@code ruleId}, because three URIs are three places someone has to go and fix something,
 * and they are routinely three different assets. Collapsing them would close the whole alert
 * the moment the first URI was patched.
 *
 * <h2>Risk</h2>
 *
 * <p>{@code riskcode} is {@code 0} informational, {@code 1} low, {@code 2} medium and
 * {@code 3} high. ZAP has no critical level, and none is invented here: a
 * {@code CRITICAL} that only ever came from the highest value a scanner happens to emit would
 * make ZAP's worst finding outrank a hand-triaged critical from a pentest. Informational
 * lands on {@code LOW} because the contract has nothing below it — the import service is
 * where a policy of dropping them would belong, not here.
 *
 * <h2>Fields ZAP has and this contract does not</h2>
 *
 * <p>{@code cweid} and {@code wascid} are read past deliberately: there is no column for
 * either, and stuffing them into the description would corrupt the scanner's own text.
 */
@Slf4j
@Component
public class ZapJsonParser implements ScannerParser {

    /**
     * A private mapper, not the injected bean. Reading a report needs no configuration the
     * shared one lacks, and the one thing that must never happen is a parser calling
     * {@code configure(...)} on the application's singleton — that would change how every
     * response in the API is serialized, from a code path nobody associates with serialization.
     */
    private static final ObjectReader READER = new ObjectMapper().readerFor(JsonNode.class);

    private static final String SITE = "site";
    private static final String ALERTS = "alerts";
    private static final String INSTANCES = "instances";

    @Override
    public ScanFormat format() {
        return ScanFormat.ZAP_JSON;
    }

    @Override
    public List<ScanFinding> parse(InputStream input) {
        InputStream content = ScanNormalizer.requireContent(input, "ZAP");
        JsonNode root;
        try {
            root = READER.readTree(content);
        } catch (JsonProcessingException ex) {
            log.debug("Relatório ZAP malformado: {}", ex.getOriginalMessage());
            throw new ScanParseException("O relatório ZAP enviado não é um JSON válido");
        } catch (IOException ex) {
            throw new ScanParseException("Não foi possível ler o relatório ZAP enviado");
        }

        List<ScanFinding> findings = new ArrayList<>();
        // The report instant: ZAP stamps only the report itself, and with a locale-formatted
        // string that is not worth guessing at. No alert carries a time of its own.
        Instant discoveredAt = Instant.now();
        for (JsonNode site : each(root.path(SITE))) {
            String siteName = text(site, "@name");
            for (JsonNode alert : each(site.path(ALERTS))) {
                try {
                    collect(alert, siteName, discoveredAt, findings);
                } catch (RuntimeException ex) {
                    // One unreadable alert must not cost the rest of the report.
                    log.debug("Alerta ZAP ignorado: {}", ex.getMessage());
                }
            }
        }
        return findings;
    }

    private void collect(JsonNode alert, String siteName, Instant discoveredAt,
                         List<ScanFinding> findings) {
        String title = ScanNormalizer.title(text(alert, "name"));
        if (title == null) {
            return;
        }
        String ruleId = trimToNull(text(alert, "pluginid"));
        String description = ScanNormalizer.description(text(alert, "desc"));
        Severity severity = severity(text(alert, "riskcode"));
        BigDecimal cvss = ScanNormalizer.cvss(firstText(alert, "cvssScore", "cvss"));
        // ZAP may put a whole list in one string; ScanNormalizer keeps the first legal one.
        String cve = ScanNormalizer.cve(firstText(alert, "cveid", "cve"));

        List<String> targets = targets(alert, siteName);
        for (String target : targets) {
            findings.add(new ScanFinding(ruleId, title, description, severity, cvss, cve, target,
                    discoveredAt,
                    ScanNormalizer.fingerprint(ScanFormat.ZAP_JSON, ruleId, target, cve)));
        }
    }

    /** One finding per instance URI; an alert without instances still belongs to its site. */
    private static List<String> targets(JsonNode alert, String siteName) {
        List<String> targets = new ArrayList<>();
        for (JsonNode instance : each(alert.path(INSTANCES))) {
            String uri = trimToNull(text(instance, "uri"));
            if (uri != null) {
                targets.add(uri);
            }
        }
        if (targets.isEmpty()) {
            targets.add(trimToNull(siteName));
        }
        return targets;
    }

    /**
     * An absent or unrecognised {@code riskcode} is {@code MEDIUM}: it means the report did not
     * say, which is not the same statement as "informational" and must not be filed as one.
     */
    private static Severity severity(String riskcode) {
        if (riskcode == null) {
            return Severity.MEDIUM;
        }
        switch (riskcode.trim()) {
            case "0":
            case "1":
                return Severity.LOW;
            case "2":
                return Severity.MEDIUM;
            case "3":
                return Severity.HIGH;
            default:
                return Severity.MEDIUM;
        }
    }

    private static Iterable<JsonNode> each(JsonNode node) {
        // A single-site report is sometimes an object where the schema says array.
        if (node.isArray()) {
            return node;
        }
        List<JsonNode> single = new ArrayList<>();
        if (node.isObject()) {
            single.add(node);
        }
        return single;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = trimToNull(text(node, field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
