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
import java.util.List;
import org.junit.jupiter.api.Test;

class ZapJsonParserTest {

    private final ZapJsonParser parser = new ZapJsonParser();

    @Test
    void announcesTheFormatTheImportServiceSelectsItBy() {
        assertThat(parser.format()).isEqualTo(ScanFormat.ZAP_JSON);
    }

    @Test
    void turnsEveryAlertInstanceIntoItsOwnFinding() {
        List<ScanFinding> findings = parse();

        assertThat(findings).hasSize(6);
        List<ScanFinding> xss = findings.subList(0, 3);
        assertThat(xss).extracting(ScanFinding::getRuleId).containsOnly("40012");
        assertThat(xss).extracting(ScanFinding::getTarget).containsExactly(
                "https://web.acme.test/search?q=1",
                "https://web.acme.test/login",
                "https://web.acme.test/comments");
        assertThat(xss).extracting(ScanFinding::getFingerprint).doesNotHaveDuplicates();
    }

    @Test
    void mapsAnAlertFieldByField() {
        ScanFinding log4shell = parse().get(3);

        assertThat(log4shell.getRuleId()).isEqualTo("10038");
        assertThat(log4shell.getTitle()).isEqualTo("Remote Code Execution - Log4Shell");
        assertThat(log4shell.getDescription()).contains("Log4j");
        assertThat(log4shell.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(log4shell.getCvssScore().toPlainString()).isEqualTo("7.5");
        assertThat(log4shell.getTarget()).isEqualTo("https://web.acme.test/api/echo");
        assertThat(log4shell.getDiscoveredAt()).isNotNull();
        assertThat(log4shell.getFingerprint()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void keepsExactlyOneCveWhenTheAlertCrowdsSeveralIntoOneString() {
        assertThat(parse().get(3).getCve()).isEqualTo("CVE-2021-44228");
    }

    @Test
    void mapsRiskcodeIncludingTheInformationalLevelZap() {
        List<ScanFinding> findings = parse();

        assertThat(findings.get(0).getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(findings.get(4).getSeverity()).isEqualTo(Severity.LOW);
        assertThat(findings.get(5).getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(findings).extracting(ScanFinding::getSeverity).doesNotContain(Severity.CRITICAL);
    }

    @Test
    void fallsBackToTheSiteWhenAnAlertHasNoInstance() {
        ScanFinding clickjacking = parse().get(5);

        assertThat(clickjacking.getRuleId()).isEqualTo("10020");
        assertThat(clickjacking.getTarget()).isEqualTo("https://web.acme.test");
    }

    @Test
    void skipsAnAlertWithoutAName() {
        assertThat(parse()).extracting(ScanFinding::getRuleId).doesNotContain("10096");
    }

    @Test
    void rejectsAReportThatIsNotJson() {
        assertThatThrownBy(() -> parser.parse(stream("{\"site\": [")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsAnEmptyReport() {
        assertThatThrownBy(() -> parser.parse(stream("\n")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("vazio");
    }

    private List<ScanFinding> parse() {
        try (InputStream input = getClass().getResourceAsStream("/scan-fixtures/zap-report.json")) {
            assertThat(input).isNotNull();
            return parser.parse(input);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
