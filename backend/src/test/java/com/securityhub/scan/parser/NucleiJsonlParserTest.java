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
import java.util.List;
import org.junit.jupiter.api.Test;

class NucleiJsonlParserTest {

    private final NucleiJsonlParser parser = new NucleiJsonlParser();

    @Test
    void announcesTheFormatTheImportServiceSelectsItBy() {
        assertThat(parser.format()).isEqualTo(ScanFormat.NUCLEI_JSONL);
    }

    @Test
    void skipsTheBrokenLineAndKeepsEveryValidOne() {
        List<ScanFinding> findings = parse();

        assertThat(findings).extracting(ScanFinding::getRuleId)
                .containsExactly("CVE-2021-44228", "tech-detect", "springboot-actuators");
    }

    @Test
    void mapsAFindingFieldByField() {
        ScanFinding log4j = parse().get(0);

        assertThat(log4j.getRuleId()).isEqualTo("CVE-2021-44228");
        assertThat(log4j.getTitle()).isEqualTo("Apache Log4j2 Remote Code Execution");
        assertThat(log4j.getDescription()).startsWith("Apache Log4j2 JNDI");
        assertThat(log4j.getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(log4j.getCvssScore().toPlainString()).isEqualTo("10.0");
        assertThat(log4j.getCve()).isEqualTo("CVE-2021-44228");
        assertThat(log4j.getTarget()).isEqualTo("https://web.acme.test/api/echo");
        assertThat(log4j.getDiscoveredAt()).isEqualTo(Instant.parse("2023-11-13T10:15:00.123456789Z"));
        assertThat(log4j.getFingerprint()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void keepsTheFirstCveOfTheClassificationArray() {
        ScanFinding actuator = parse().get(2);

        assertThat(actuator.getCve()).isEqualTo("CVE-2023-1234");
        assertThat(actuator.getCvssScore().toPlainString()).isEqualTo("7.5");
    }

    @Test
    void filesAnInformationalFindingAsLow() {
        ScanFinding detection = parse().get(1);

        assertThat(detection.getSeverity()).isEqualTo(Severity.LOW);
        assertThat(detection.getCvssScore()).isNull();
        assertThat(detection.getCve()).isNull();
        assertThat(detection.getTarget()).isEqualTo("http://api.acme.test");
    }

    @Test
    void skipsALineWithoutAName() {
        assertThat(parse()).extracting(ScanFinding::getRuleId).doesNotContain("sem-nome");
    }

    @Test
    void rejectsAnEmptyReport() {
        assertThatThrownBy(() -> parser.parse(stream("   ")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("vazio");
    }

    /** Tolerance starts once the stream is recognisable as JSONL; the wrong file is refused. */
    @Test
    void rejectsAReportWhoseFirstBytesAreNotJsonAtAll() {
        assertThatThrownBy(() -> parser.parse(stream("nao e json\noutra linha solta\n")))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("JSONL");
    }

    private List<ScanFinding> parse() {
        try (InputStream input =
                     getClass().getResourceAsStream("/scan-fixtures/nuclei-findings.jsonl")) {
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
