package com.securityhub.attachment;

import com.securityhub.shared.error.BadRequestException;

/**
 * Cleans the filename the client sent so it can be stored and announced back on download.
 *
 * <p>Nothing here builds a path. The file on disk is named by a server-generated UUID
 * ({@link FilesystemAttachmentStorage}), so the worst a hostile name can achieve is a
 * misleading label in a listing. This class keeps even that from happening, and it does the
 * traversal stripping anyway — a future reader must not be able to find a way to reach the
 * filesystem with this string and succeed.
 */
public final class AttachmentFilenameSanitizer {

    static final int MAX_LENGTH = 200;

    /** Illegal on Windows, and {@code /} {@code \} are what a traversal is made of. */
    private static final String FORBIDDEN_CHARACTERS = "\"\\/:*?<>|";

    private static final int MAX_EXTENSION_LENGTH = 12;

    private AttachmentFilenameSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            throw invalid();
        }
        // Internet Explorer's habit outlived it: browsers still send "C:\Users\ana\prova.pdf"
        // for a file picked from the desktop, so only the last segment is a name at all.
        String segment = lastSegment(raw.trim());
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw invalid();
        }

        String cleaned = collapseWhitespace(removeForbidden(segment));
        if (cleaned.isEmpty()) {
            throw invalid();
        }
        return truncate(cleaned);
    }

    private static String lastSegment(String raw) {
        int separator = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        return separator < 0 ? raw : raw.substring(separator + 1);
    }

    private static String removeForbidden(String segment) {
        StringBuilder kept = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char current = segment.charAt(i);
            if (current < '\u0020' || current == '\u007F') {
                continue;
            }
            if (FORBIDDEN_CHARACTERS.indexOf(current) >= 0) {
                continue;
            }
            kept.append(current);
        }
        return kept.toString();
    }

    /** A run of spaces or tabs becomes one space; a name is a label, not a layout. */
    private static String collapseWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Truncation keeps the extension, because the extension is the part a person uses to
     * recognise what the file is; losing it turns {@code relatorio....pdf} into something the
     * operating system no longer knows how to open.
     */
    private static String truncate(String value) {
        if (value.length() <= MAX_LENGTH) {
            return value;
        }
        int dot = value.lastIndexOf('.');
        String extension = "";
        if (dot > 0 && dot < value.length() - 1 && value.length() - dot <= MAX_EXTENSION_LENGTH) {
            extension = value.substring(dot);
        }
        return value.substring(0, MAX_LENGTH - extension.length()) + extension;
    }

    private static BadRequestException invalid() {
        return new BadRequestException("Nome de arquivo inválido");
    }
}
