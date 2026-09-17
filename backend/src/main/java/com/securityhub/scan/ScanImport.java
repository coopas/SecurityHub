package com.securityhub.scan;

import com.securityhub.company.Company;
import com.securityhub.project.Project;
import com.securityhub.shared.model.BaseEntity;
import com.securityhub.user.User;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

/**
 * One uploaded report and the summary of what it contained.
 *
 * <p>There is no mapped collection of findings here on purpose, the same choice
 * {@code Asset} makes about its vulnerabilities: a listing of twenty imports would initialize
 * twenty collections to render six counters that are already on the row. The findings are read
 * through {@code ScanFindingRepository} by the one method that needs them.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@BatchSize(size = 50)
@Table(name = "scan_imports")
public class ScanImport extends BaseEntity {

    /** Denormalized tenant, always equal to {@code project.company}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    /**
     * Unlike {@code Vulnerability}, an import does keep its project, and it cannot go stale:
     * an asset may be moved between projects, an import may not. This records the project the
     * operator chose at upload time, which is also the scope the targets were resolved in.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;

    /** Declared by the caller, never sniffed from the bytes; see {@link ScanFormat}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ScanFormat format;

    /** The sanitized client name. Shown in the history, never used to build a path. */
    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 32, updatable = false)
    private String storedFilename;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanImportStatus status = ScanImportStatus.PENDING;

    @Column(name = "total_findings", nullable = false)
    private int totalFindings;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "unmatched_count", nullable = false)
    private int unmatchedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    /** Nullable: removing a user must not remove the record that the import happened. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_by", updatable = false)
    private User importedBy;

    public ScanImport(Company company, Project project, ScanFormat format, String originalFilename,
                      String storedFilename, long sizeBytes, User importedBy) {
        this.company = company;
        this.project = project;
        this.format = format;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.sizeBytes = sizeBytes;
        // An import always starts as a proposal; only confirm and discard move it from here.
        this.status = ScanImportStatus.PENDING;
        this.importedBy = importedBy;
    }
}
