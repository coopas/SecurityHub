package com.securityhub.report;

import lombok.Getter;

/**
 * The generated document and the name the server chose for it, which is the whole contract
 * between {@link ReportService} and {@link ReportController}. The client never names a file the
 * server writes.
 */
@Getter
public final class ExecutiveReport {

    private final byte[] content;
    private final String filename;

    public ExecutiveReport(byte[] content, String filename) {
        this.content = content;
        this.filename = filename;
    }
}
