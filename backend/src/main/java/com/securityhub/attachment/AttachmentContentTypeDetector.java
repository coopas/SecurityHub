package com.securityhub.attachment;

import com.securityhub.shared.error.UnsupportedMediaTypeException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides the content type of an upload from its bytes.
 *
 * <p>The declared {@code Content-Type} of the multipart part is ignored completely. It is a
 * header the uploader writes, so an allowlist checked against it is decoration: anything at
 * all can claim to be {@code application/pdf}. The bytes are the only part of an upload the
 * client does not get to lie about.
 *
 * <p>The allowlist is a constant and not a property. An allowlist that an environment
 * variable can widen is not an allowlist — it is a default, and the one deployment that
 * widens it is the one nobody reviews.
 *
 * <p>Worth stating plainly, because it looks like a hole: an SVG is well-formed UTF-8 text
 * and is accepted as {@code text/plain}. That is safe, and it is safe for a reason that has
 * nothing to do with this class. Safety comes from the response: every download is served
 * with {@code Content-Disposition: attachment}, with the stored content type, and with
 * {@code X-Content-Type-Options: nosniff}, which {@code SecurityConfig} already sets on every
 * response — so the browser saves the file instead of rendering it, and never re-sniffs it
 * into {@code image/svg+xml}. What this sniff is for is keeping the stored type honest, and
 * keeping executables, archives and office documents out of the directory entirely.
 */
public final class AttachmentContentTypeDetector {

    public static final String PDF = "application/pdf";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String PLAIN_TEXT = "text/plain";

    private static final Set<String> ALLOWED_CONTENT_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(PDF, PNG, JPEG, PLAIN_TEXT)));

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /** Decoding slice. Large enough to be one pass for every realistic text attachment. */
    private static final int DECODE_CHUNK = 8192;

    private AttachmentContentTypeDetector() {
    }

    public static Set<String> allowedContentTypes() {
        return ALLOWED_CONTENT_TYPES;
    }

    /**
     * @throws UnsupportedMediaTypeException when the bytes are not one of the four accepted
     *                                       types
     */
    public static String detect(byte[] content) {
        if (content == null || content.length == 0) {
            throw unsupported();
        }
        if (startsWith(content, PDF_SIGNATURE)) {
            return PDF;
        }
        if (startsWith(content, PNG_SIGNATURE)) {
            return PNG;
        }
        if (startsWith(content, JPEG_SIGNATURE)) {
            return JPEG;
        }
        if (isPlainText(content)) {
            return PLAIN_TEXT;
        }
        throw unsupported();
    }

    private static UnsupportedMediaTypeException unsupported() {
        return new UnsupportedMediaTypeException(
                "O conteúdo enviado não é PDF, PNG, JPEG ou texto simples");
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strict UTF-8 with no C0 control other than tab, LF and CR. Strictness is what rejects
     * the binaries: an ELF carries NUL padding in its identification bytes and a ZIP — and
     * therefore a DOCX or an XLSX — starts with {@code PK} followed by {@code 0x03 0x04}.
     *
     * <p>The input is fed in slices, and every slice but the last is decoded with
     * {@code endOfInput = false}. That is the load-bearing argument: a multi-byte character
     * straddling a slice boundary then reports UNDERFLOW, meaning "give me more bytes",
     * instead of MALFORMED — the unconsumed bytes are compacted back to the front and decoded
     * together with the next slice. With {@code true} there, a perfectly valid accented file
     * would be rejected purely because of where 8192 fell.
     */
    private static boolean isPlainText(byte[] content) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.allocate(DECODE_CHUNK);
        // One byte never decodes to more than one char, so the output can never overflow.
        CharBuffer out = CharBuffer.allocate(DECODE_CHUNK);

        int offset = 0;
        while (true) {
            int length = Math.min(in.remaining(), content.length - offset);
            in.put(content, offset, length);
            offset += length;
            boolean endOfInput = offset >= content.length;

            in.flip();
            CoderResult result = decoder.decode(in, out, endOfInput);
            if (result.isError()) {
                return false;
            }
            out.flip();
            if (containsControlCharacter(out)) {
                return false;
            }
            out.clear();
            in.compact();

            if (endOfInput) {
                // Bytes still pending with no more input to come: a truncated sequence.
                return in.position() == 0 && !decoder.flush(out).isError();
            }
        }
    }

    private static boolean containsControlCharacter(CharBuffer decoded) {
        while (decoded.hasRemaining()) {
            char current = decoded.get();
            if (current == '\t' || current == '\n' || current == '\r') {
                continue;
            }
            if (current < ' ') {
                return true;
            }
        }
        return false;
    }
}
