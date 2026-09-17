package com.securityhub.scan.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ScanNormalizerTest {

    @Test
    void roundsCvssToTheSingleDecimalTheColumnStores() {
        assertThat(ScanNormalizer.cvss("7.53").toPlainString()).isEqualTo("7.5");
        assertThat(ScanNormalizer.cvss("7.55").toPlainString()).isEqualTo("7.6");
        assertThat(ScanNormalizer.cvss("10").toPlainString()).isEqualTo("10.0");
        assertThat(ScanNormalizer.cvss("0").toPlainString()).isEqualTo("0.0");
    }

    @Test
    void dropsACvssOutsideTheScaleInsteadOfClampingIt() {
        assertThat(ScanNormalizer.cvss("11.0")).isNull();
        assertThat(ScanNormalizer.cvss("-1")).isNull();
        assertThat(ScanNormalizer.cvss("altissimo")).isNull();
        assertThat(ScanNormalizer.cvss("  ")).isNull();
        assertThat(ScanNormalizer.cvss(null)).isNull();
    }

    @Test
    void truncatesTitleAndDescriptionToTheColumnWidths() {
        String longTitle = repeat('a', 300);
        String longDescription = repeat('b', 5000);

        assertThat(ScanNormalizer.title(longTitle)).hasSize(200);
        assertThat(ScanNormalizer.description(longDescription)).hasSize(4000);
        assertThat(ScanNormalizer.title("  XSS refletido  ")).isEqualTo("XSS refletido");
    }

    @Test
    void reportsNoTitleForBlankInputSoTheParserCanSkipTheFinding() {
        assertThat(ScanNormalizer.title("   ")).isNull();
        assertThat(ScanNormalizer.title("")).isNull();
        assertThat(ScanNormalizer.title(null)).isNull();
    }

    @Test
    void upperCasesAValidCveAndRejectsAnythingElse() {
        assertThat(ScanNormalizer.cve("cve-2021-44228")).isEqualTo("CVE-2021-44228");
        assertThat(ScanNormalizer.cve("  CVE-2017-0143  ")).isEqualTo("CVE-2017-0143");
        assertThat(ScanNormalizer.cve("not-a-cve")).isNull();
        assertThat(ScanNormalizer.cve("CVE-21-4422")).isNull();
        assertThat(ScanNormalizer.cve((String) null)).isNull();
    }

    @Test
    void keepsOnlyTheFirstCveWhenAScannerCrowdsSeveralIntoOneField() {
        assertThat(ScanNormalizer.cve("CVE-2021-44228, CVE-2021-45046")).isEqualTo("CVE-2021-44228");
        assertThat(ScanNormalizer.cve("IDs: CVE:CVE-2017-0143 Risk factor: HIGH"))
                .isEqualTo("CVE-2017-0143");
        assertThat(ScanNormalizer.cve(Arrays.asList("nao-e-cve", "CVE-2023-5678")))
                .isEqualTo("CVE-2023-5678");
        assertThat(ScanNormalizer.cve(Collections.<String>emptyList())).isNull();
    }

    /** A legal CVE shape can still be too long for {@code VARCHAR(20)}; the column decides. */
    @Test
    void rejectsACveThatWouldNotFitTheColumn() {
        assertThat(ScanNormalizer.cve("CVE-2021-1234567890123456")).isNull();
    }

    @Test
    void producesLowercaseHexThatRepeatsForTheSameFinding() {
        String first = ScanNormalizer.fingerprint(ScanFormat.ZAP_JSON, "40012",
                "https://web.acme.test/login", "CVE-2021-44228");
        String second = ScanNormalizer.fingerprint(ScanFormat.ZAP_JSON, "40012",
                "https://web.acme.test/login", "CVE-2021-44228");

        assertThat(first).isEqualTo(second).matches("^[0-9a-f]{64}$");
    }

    @Test
    void changesTheFingerprintWhenTheTargetChanges() {
        String login = ScanNormalizer.fingerprint(ScanFormat.ZAP_JSON, "40012",
                "https://web.acme.test/login", null);
        String search = ScanNormalizer.fingerprint(ScanFormat.ZAP_JSON, "40012",
                "https://web.acme.test/search", null);

        assertThat(login).isNotEqualTo(search);
    }

    /**
     * The regression this guards: a scanner re-scoring its own rule between releases must not
     * make the next import create a second row for a vulnerability someone is already on.
     */
    @Test
    void ignoresSeverityAndCvssEntirely() {
        String before = ScanNormalizer.fingerprint(ScanFormat.NUCLEI_JSONL, "springboot-actuators",
                "http://api.acme.test/actuator/env", "CVE-2023-1234");
        String after = ScanNormalizer.fingerprint(ScanFormat.NUCLEI_JSONL, "springboot-actuators",
                "http://api.acme.test/actuator/env", "CVE-2023-1234");

        assertThat(before).isEqualTo(after);
        assertThat(ScanNormalizer.fingerprint(ScanFormat.NUCLEI_JSONL, "springboot-actuators",
                "http://api.acme.test/actuator/env", null)).isNotEqualTo(before);
    }

    @Test
    void refusesAReportWithNoContentAtAll() {
        assertThatThrownBy(() -> ScanNormalizer.requireContent(stream("   \n\t  "), "nuclei"))
                .isInstanceOf(ScanParseException.class)
                .hasMessageContaining("vazio");
        assertThatThrownBy(() -> ScanNormalizer.requireContent(stream(""), "nmap"))
                .isInstanceOf(ScanParseException.class);
        assertThatThrownBy(() -> ScanNormalizer.requireContent(null, "ZAP"))
                .isInstanceOf(ScanParseException.class);
    }

    @Test
    void handsBackAStreamStillPositionedAtTheFirstByte() throws Exception {
        byte[] content = new byte[16];
        int read = ScanNormalizer.requireContent(stream("  {\"a\":1}"), "nuclei").read(content);

        assertThat(new String(content, 0, read, StandardCharsets.UTF_8)).isEqualTo("  {\"a\":1}");
    }

    private static ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(char character, int times) {
        char[] value = new char[times];
        Arrays.fill(value, character);
        return new String(value);
    }
}
