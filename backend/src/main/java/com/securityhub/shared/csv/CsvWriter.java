package com.securityhub.shared.csv;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RFC 4180 writer with one deliberate omission: there is no method that writes a raw
 * cell. The caller hands over raw domain values and cannot opt out of
 * {@link CsvSanitizer#cell(String)}, so a column added to the export next year is escaped
 * without anyone having to remember. A {@code writeRawRow} would make the escaping a
 * convention again, and conventions are what the sanitizer exists to replace — do not add one.
 */
public final class CsvWriter implements Closeable {

    private static final char DELIMITER = ',';
    private static final String TERMINATOR = "\r\n";

    /**
     * Excel on Windows opens a double-clicked {@code .csv} with the system ANSI code page,
     * not UTF-8, so {@code crítica} renders as {@code crÃ­tica}. The byte order mark is the
     * only signal that code path honours, and it costs three bytes. Everything else that
     * reads CSV either detects it or skips it.
     */
    private static final String BYTE_ORDER_MARK = "﻿";

    private final Writer writer;

    public CsvWriter(OutputStream outputStream) throws IOException {
        this.writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        this.writer.write(BYTE_ORDER_MARK);
    }

    public void writeRow(String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                writer.write(DELIMITER);
            }
            writer.write(CsvSanitizer.cell(cells[i]));
        }
        writer.write(TERMINATOR);
    }

    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
