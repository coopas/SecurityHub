package com.securityhub.scan;

/**
 * Lifecycle of one uploaded report.
 *
 * <p>There are exactly three states and no way back from either terminal one. An import is
 * {@code PENDING} while it is a proposal — rows in {@code scan_findings} and a file on disk
 * that changed nothing in the backlog — and it stops being a proposal the moment someone
 * decides. {@code CONFIRMED} means vulnerabilities were created from it and is why the report
 * file is kept; {@code DISCARDED} means nothing was created and is why the file is deleted.
 *
 * <p>No {@code PROCESSING} state, because there is nothing to process asynchronously: the
 * upload parses, matches and stages inside the request. A status a caller would have to poll
 * would be describing work that has already finished by the time the response is written.
 */
public enum ScanImportStatus {
    PENDING,
    CONFIRMED,
    DISCARDED;

    public boolean isPending() {
        return this == PENDING;
    }
}
