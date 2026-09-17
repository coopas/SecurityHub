package com.securityhub.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.shared.error.UnsupportedMediaTypeException;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AttachmentContentTypeDetectorTest {

    private static final byte[] PDF = bytes("%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n");

    private static final byte[] PNG = concat(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
            new byte[]{0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'});

    private static final byte[] JPEG = concat(
            new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            bytes("JFIF"));

    /** A real e_ident: the seven padding bytes after EI_ABIVERSION are NUL, which is the tell. */
    private static final byte[] ELF = concat(
            new byte[]{0x7F, 'E', 'L', 'F', 0x02, 0x01, 0x01, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
            new byte[]{0x02, 0x00, 0x3E, 0x00, 0x01, 0x00, 0x00, 0x00});

    private static final byte[] ZIP = concat(
            new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00},
            bytes("conteudo"));

    /** A DOCX is a ZIP whose first entry is [Content_Types].xml; the container is what is seen. */
    private static final byte[] DOCX = concat(
            new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x06, 0x00},
            bytes("[Content_Types].xml"));

    @Test
    void acceptsAPdf() {
        assertThat(AttachmentContentTypeDetector.detect(PDF))
                .isEqualTo(AttachmentContentTypeDetector.PDF);
    }

    @Test
    void acceptsAPng() {
        assertThat(AttachmentContentTypeDetector.detect(PNG))
                .isEqualTo(AttachmentContentTypeDetector.PNG);
    }

    @Test
    void acceptsAJpeg() {
        assertThat(AttachmentContentTypeDetector.detect(JPEG))
                .isEqualTo(AttachmentContentTypeDetector.JPEG);
    }

    @Test
    void acceptsAccentedTextWithTabsAndNewlines() {
        byte[] content = bytes("evidência\tcolhida\r\nem produção — sem correção\n");

        assertThat(AttachmentContentTypeDetector.detect(content))
                .isEqualTo(AttachmentContentTypeDetector.PLAIN_TEXT);
    }

    @Test
    void rejectsAnExecutable() {
        assertThatThrownBy(() -> AttachmentContentTypeDetector.detect(ELF))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }

    @Test
    void rejectsAnArchive() {
        assertThatThrownBy(() -> AttachmentContentTypeDetector.detect(ZIP))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }

    @Test
    void rejectsAnOfficeDocument() {
        assertThatThrownBy(() -> AttachmentContentTypeDetector.detect(DOCX))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }

    @Test
    void rejectsInvalidUtf8() {
        assertThatThrownBy(() -> AttachmentContentTypeDetector.detect(new byte[]{(byte) 0xC3, 0x28}))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }

    /**
     * The test that proves the declared header is never consulted: the part announces a PDF and
     * carries an executable. If the detector trusted {@code Content-Type}, the allowlist would
     * be decorative and this upload would be stored as {@code application/pdf}.
     */
    @Test
    void ignoresTheDeclaredContentTypeOfThePart() throws IOException {
        MockMultipartFile part = new MockMultipartFile("file", "relatorio.pdf", "application/pdf", ELF);

        assertThatThrownBy(() -> AttachmentContentTypeDetector.detect(part.getBytes()))
                .isInstanceOf(UnsupportedMediaTypeException.class);
    }

    /**
     * The two bytes of {@code é} straddle the 8192-byte decoding slice. With
     * {@code endOfInput = true} on a non-final slice the decoder would call that malformed and
     * a perfectly ordinary accented file would come back as 415.
     */
    @Test
    void acceptsAMultiByteCharacterSplitAcrossTheDecodingBoundary() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8191; i++) {
            text.append('a');
        }
        text.append("é fim");
        byte[] content = bytes(text.toString());
        assertThat(content[8191]).isEqualTo((byte) 0xC3);

        assertThat(AttachmentContentTypeDetector.detect(content))
                .isEqualTo(AttachmentContentTypeDetector.PLAIN_TEXT);
    }

    @Test
    void theAllowlistIsExactlyTheFourAcceptedTypes() {
        assertThat(AttachmentContentTypeDetector.allowedContentTypes())
                .containsExactlyInAnyOrder("application/pdf", "image/png", "image/jpeg", "text/plain");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(first.length + second.length);
        buffer.write(first, 0, first.length);
        buffer.write(second, 0, second.length);
        return buffer.toByteArray();
    }
}
