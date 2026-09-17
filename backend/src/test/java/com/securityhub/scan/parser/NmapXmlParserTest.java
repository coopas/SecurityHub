package com.securityhub.scan.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.scan.ScanFinding;
import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import com.securityhub.vulnerability.Severity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NmapXmlParserTest {

    private final NmapXmlParser parser = new NmapXmlParser();

    @Test
    void announcesTheFormatTheImportServiceSelectsItBy() {
        assertThat(parser.format()).isEqualTo(ScanFormat.NMAP_XML);
    }

    @Test
    void readsOnlyNseScriptResults() {
        List<ScanFinding> findings = parse("nmap-vuln.xml");

        assertThat(findings).extracting(ScanFinding::getRuleId)
                .containsExactly("ssl-heartbleed", "http-server-header", "smb-vuln-ms17-010");
    }

    @Test
    void ignoresOpenPortsAndServiceBanners() {
        List<ScanFinding> findings = parse("nmap-vuln.xml");

        assertThat(findings).extracting(ScanFinding::getRuleId)
                .doesNotContain("http", "https", "ssh", "80", "22");
        assertThat(findings).extracting(ScanFinding::getDescription)
                .noneMatch(description -> description != null && description.contains("OpenSSH"));
    }

    @Test
    void mapsAScriptResultFieldByField() {
        ScanFinding heartbleed = parse("nmap-vuln.xml").get(0);

        assertThat(heartbleed.getRuleId()).isEqualTo("ssl-heartbleed");
        assertThat(heartbleed.getTitle()).isEqualTo("ssl-heartbleed");
        assertThat(heartbleed.getDescription()).startsWith("VULNERABLE: The Heartbleed Bug");
        assertThat(heartbleed.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(heartbleed.getCvssScore()).isNull();
        assertThat(heartbleed.getCve()).isEqualTo("CVE-2014-0160");
        assertThat(heartbleed.getTarget()).isEqualTo("web.acme.test");
        assertThat(heartbleed.getDiscoveredAt()).isEqualTo(Instant.ofEpochSecond(1700000000L));
        assertThat(heartbleed.getFingerprint()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void prefersTheHostnameAndFallsBackToTheAddress() {
        List<ScanFinding> findings = parse("nmap-vuln.xml");

        assertThat(findings.get(1).getTarget()).isEqualTo("web.acme.test");
        assertThat(findings.get(2).getTarget()).isEqualTo("10.0.0.11");
    }

    @Test
    void callsAScriptHighOnlyWhenItSaysSomethingIsVulnerable() {
        List<ScanFinding> findings = parse("nmap-vuln.xml");

        assertThat(findings.get(0).getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(findings.get(1).getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(findings.get(1).getCve()).isNull();
        assertThat(findings.get(2).getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(findings.get(2).getCve()).isEqualTo("CVE-2017-0143");
    }

    @Test
    void skipsAScriptWithoutAnId() {
        assertThat(parse("nmap-vuln.xml")).extracting(ScanFinding::getTitle).doesNotContainNull();
    }

    /** The reason the factory is hardened: the payload must never reach a field. */
    @Test
    void neverExpandsAnExternalEntity() {
        assertThat(everyTextOf(parseTolerating("nmap-xxe.xml")))
                .noneMatch(value -> value.contains("root:") || value.contains("/bin/"));
    }

    /**
     * A recusa é a asserção, e não a ausência do conteúdo: sem {@code SUPPORT_DTD} desligado
     * o StAX resolve a entidade em silêncio e o documento parseia normalmente, então um teste
     * que apenas procurasse por {@code root:} nos campos continuaria verde com a proteção
     * removida. Verificado à mão: com as travas desligadas, um arquivo apontado por entidade
     * externa num nó de texto é lido integralmente.
     */
    @Test
    void rejectsADocumentThatDeclaresAnExternalEntity() {
        assertThatThrownBy(() -> parse("nmap-xxe.xml"))
                .isInstanceOf(ScanParseException.class);
    }

    @Test
    void neverExpandsRecursiveEntities() {
        List<ScanFinding> findings = parseTolerating("nmap-billion-laughs.xml");

        assertThat(everyTextOf(findings)).noneMatch(value -> value.contains("hahaha"));
        assertThat(everyTextOf(findings)).allMatch(value -> value.length() <= 4000);
    }

    @Test
    void rejectsAReportThatIsNotXml() {
        assertThatThrownBy(() -> parser.parse(stream("isto nao e um relatorio")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("XML");
    }

    @Test
    void rejectsAnEmptyReport() {
        assertThatThrownBy(() -> parser.parse(stream("  \n ")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("vazio");
    }

    /** Either outcome is a pass: the document is refused, or it yields nothing expanded. */
    private List<ScanFinding> parseTolerating(String fixture) {
        try {
            return parse(fixture);
        } catch (ScanParseException ex) {
            return Collections.emptyList();
        }
    }

    private static List<String> everyTextOf(List<ScanFinding> findings) {
        List<String> values = new ArrayList<>();
        for (ScanFinding finding : findings) {
            addIfPresent(values, finding.getRuleId());
            addIfPresent(values, finding.getTitle());
            addIfPresent(values, finding.getDescription());
            addIfPresent(values, finding.getTarget());
            addIfPresent(values, finding.getCve());
        }
        return values;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null) {
            values.add(value);
        }
    }

    private List<ScanFinding> parse(String fixture) {
        try (InputStream input = getClass().getResourceAsStream("/scan-fixtures/" + fixture)) {
            assertThat(input).as("fixture %s", fixture).isNotNull();
            return parser.parse(input);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
