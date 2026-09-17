package com.securityhub.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.shared.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentFilenameSanitizerTest {

    @Test
    void keepsAnOrdinaryName() {
        assertThat(AttachmentFilenameSanitizer.sanitize("relatório final.pdf"))
                .isEqualTo("relatório final.pdf");
    }

    /** Browsers still send the whole client-side path for a file picked from the desktop. */
    @Test
    void keepsOnlyTheFinalSegmentOfAWindowsPath() {
        assertThat(AttachmentFilenameSanitizer.sanitize("C:\\Users\\ana\\r.pdf")).isEqualTo("r.pdf");
    }

    @Test
    void keepsOnlyTheFinalSegmentOfAPosixPath() {
        assertThat(AttachmentFilenameSanitizer.sanitize("/home/ana/evidências/print.png"))
                .isEqualTo("print.png");
    }

    @Test
    void reducesATraversalAttemptToItsHarmlessTail() {
        assertThat(AttachmentFilenameSanitizer.sanitize("../../evil.txt")).isEqualTo("evil.txt");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", ".", "..", "a/b/", "///", "\u0000", "??", "<>|"})
    void rejectsNamesThatCannotBeCleanedIntoAnything(String raw) {
        assertThatThrownBy(() -> AttachmentFilenameSanitizer.sanitize(raw))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void stripsControlCharactersAndTheForbiddenPunctuation() {
        assertThat(AttachmentFilenameSanitizer.sanitize("re\u0007la:t*ó?ri<o>|\"x\".pdf"))
                .isEqualTo("relatóriox.pdf");
    }

    @Test
    void collapsesRunsOfWhitespace() {
        assertThat(AttachmentFilenameSanitizer.sanitize("  nome    com \t espaços .png"))
                .isEqualTo("nome com espaços .png");
    }

    @Test
    void truncatesToTheLimitWhileKeepingTheExtension() {
        String raw = repeat("a", 400) + ".pdf";

        String sanitized = AttachmentFilenameSanitizer.sanitize(raw);

        assertThat(sanitized).hasSize(AttachmentFilenameSanitizer.MAX_LENGTH);
        assertThat(sanitized).endsWith(".pdf");
    }

    @Test
    void truncatesANameWithoutAnExtension() {
        String sanitized = AttachmentFilenameSanitizer.sanitize(repeat("b", 400));

        assertThat(sanitized).hasSize(AttachmentFilenameSanitizer.MAX_LENGTH);
    }

    private static String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder(value.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
