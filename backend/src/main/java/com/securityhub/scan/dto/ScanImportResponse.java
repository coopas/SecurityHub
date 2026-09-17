package com.securityhub.scan.dto;

import java.util.List;
import lombok.Getter;

/**
 * The summary plus the findings, which is what the preview screen and every mutating endpoint
 * answer: after an upload, a mapping or a confirm, the client needs the new state of the rows
 * it is showing, not a second round trip to fetch them.
 *
 * <p>The history listing answers {@link ScanImportSummaryResponse} instead — a page of twenty
 * imports carrying every finding of each would be thousands of rows to render six numbers.
 */
@Getter
public class ScanImportResponse extends ScanImportSummaryResponse {

    private final List<ScanFindingResponse> findings;

    public ScanImportResponse(ScanImportSummaryResponse summary, List<ScanFindingResponse> findings) {
        super(summary.getId(), summary.getProjectId(), summary.getProjectName(), summary.getFormat(),
                summary.getOriginalFilename(), summary.getSizeBytes(), summary.getStatus(),
                summary.getTotalFindings(), summary.getMatchedCount(), summary.getUnmatchedCount(),
                summary.getDuplicateCount(), summary.getImportedCount(), summary.getSkippedCount(),
                summary.getImportedByName(), summary.getCreatedAt(), summary.getUpdatedAt());
        this.findings = findings;
    }
}
