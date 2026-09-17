package com.securityhub.scan.dto;

import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanImportStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One row of the import history: everything about an import except what it found.
 *
 * <p>The six counters are primitives, so they are always serialized even though
 * {@code default-property-inclusion: non_null} drops nulls elsewhere in this API. A PENDING
 * import answers {@code importedCount: 0} rather than omitting the field, which is what lets
 * the client render the same six numbers for every row of the listing.
 */
@Getter
@AllArgsConstructor
public class ScanImportSummaryResponse {

    private final Long id;
    private final Long projectId;
    private final String projectName;
    private final ScanFormat format;
    /** The sanitized name of the upload; the name on disk is never exposed. */
    private final String originalFilename;
    private final long sizeBytes;
    private final ScanImportStatus status;
    private final int totalFindings;
    private final int matchedCount;
    private final int unmatchedCount;
    private final int duplicateCount;
    private final int importedCount;
    private final int skippedCount;
    /** The name of whoever ran the import, never their id and never their e-mail. */
    private final String importedByName;
    private final Instant createdAt;
    private final Instant updatedAt;
}
