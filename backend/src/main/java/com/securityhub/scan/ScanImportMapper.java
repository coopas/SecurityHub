package com.securityhub.scan;

import com.securityhub.asset.Asset;
import com.securityhub.project.Project;
import com.securityhub.scan.dto.ScanFindingResponse;
import com.securityhub.scan.dto.ScanImportResponse;
import com.securityhub.scan.dto.ScanImportSummaryResponse;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Vulnerability;
import java.util.ArrayList;
import java.util.List;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class ScanImportMapper {

    private ScanImportMapper() {
    }

    /**
     * Touches {@code project} and {@code importedBy}, both lazy: callers must run inside the
     * service transaction because {@code open-in-view} is disabled.
     */
    public static ScanImportSummaryResponse toSummary(ScanImport scanImport) {
        Project project = scanImport.getProject();
        User importer = scanImport.getImportedBy();
        return new ScanImportSummaryResponse(
                scanImport.getId(),
                project == null ? null : project.getId(),
                project == null ? null : project.getName(),
                scanImport.getFormat(),
                scanImport.getOriginalFilename(),
                scanImport.getSizeBytes(),
                scanImport.getStatus(),
                scanImport.getTotalFindings(),
                scanImport.getMatchedCount(),
                scanImport.getUnmatchedCount(),
                scanImport.getDuplicateCount(),
                scanImport.getImportedCount(),
                scanImport.getSkippedCount(),
                importer == null ? null : importer.getName(),
                scanImport.getCreatedAt(),
                scanImport.getUpdatedAt());
    }

    public static ScanImportResponse toResponse(ScanImport scanImport, List<ScanImportFinding> findings) {
        List<ScanFindingResponse> mapped = new ArrayList<>(findings.size());
        for (ScanImportFinding finding : findings) {
            mapped.add(toFindingResponse(finding));
        }
        return new ScanImportResponse(toSummary(scanImport), mapped);
    }

    /**
     * Touches {@code asset}, which the repository fetches for this reason, and reads the id of
     * {@code vulnerability} — reading the identifier of a proxy does not initialize it, so the
     * association stays lazy and a confirmed import of four hundred findings does not become
     * four hundred selects.
     */
    public static ScanFindingResponse toFindingResponse(ScanImportFinding finding) {
        Asset asset = finding.getAsset();
        Vulnerability vulnerability = finding.getVulnerability();
        return new ScanFindingResponse(
                finding.getId(),
                finding.getRuleId(),
                finding.getTitle(),
                finding.getDescription(),
                finding.getSeverity(),
                finding.getCvssScore(),
                finding.getCve(),
                finding.getTarget(),
                finding.getDiscoveredAt(),
                finding.getStatus(),
                asset == null ? null : asset.getId(),
                asset == null ? null : asset.getName(),
                vulnerability == null ? null : vulnerability.getId());
    }
}
