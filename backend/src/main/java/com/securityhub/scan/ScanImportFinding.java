package com.securityhub.scan;

import com.securityhub.asset.Asset;
import com.securityhub.company.Company;
import com.securityhub.shared.model.BaseEntity;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.Vulnerability;
import java.math.BigDecimal;
import java.time.Instant;
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
 * One row of the staging area: a finding a parser produced, plus what the importer decided
 * about it.
 *
 * <p>Named {@code ScanImportFinding} and not {@code ScanFinding} because {@link ScanFinding} is
 * already the immutable value a parser hands over. The two are deliberately distinct types: the
 * value is what the report said and must never change, this entity is what the product decided
 * and changes twice — once when an operator maps an asset, once when the import is confirmed.
 *
 * <p>This table is also where per-finding traceability lives. The audit trail carries a single
 * row for a whole import; the answer to "which report created this vulnerability" is the
 * {@code vulnerability_id} of a row here, which is a better place for it than hundreds of
 * audit entries would be.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@BatchSize(size = 50)
@Table(name = "scan_findings")
public class ScanImportFinding extends BaseEntity {

    /** Denormalized tenant, always equal to {@code scanImport.company}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    /** The field is {@code scanImport} because {@code import} is a Java keyword. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_id", nullable = false, updatable = false)
    private ScanImport scanImport;

    @Column(name = "rule_id", length = 200, updatable = false)
    private String ruleId;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    @Column(length = 4000, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private Severity severity;

    @Column(name = "cvss_score", precision = 3, scale = 1, updatable = false)
    private BigDecimal cvssScore;

    @Column(length = 20, updatable = false)
    private String cve;

    @Column(length = 2000, updatable = false)
    private String target;

    @Column(name = "discovered_at", nullable = false, updatable = false)
    private Instant discoveredAt;

    @Column(nullable = false, length = 64, updatable = false)
    private String fingerprint;

    /**
     * Mutable and nullable: null while {@code UNMATCHED}, set either by the identifier match at
     * upload or by the operator on the preview screen. The importer never creates an asset.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScanFindingStatus status;

    /** Set exactly when the status becomes {@code IMPORTED}; V9 enforces the equivalence. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vulnerability_id")
    private Vulnerability vulnerability;

    public ScanImportFinding(Company company, ScanImport scanImport, String ruleId, String title,
                             String description, Severity severity, BigDecimal cvssScore, String cve,
                             String target, Instant discoveredAt, String fingerprint, Asset asset,
                             ScanFindingStatus status) {
        this.company = company;
        this.scanImport = scanImport;
        this.ruleId = ruleId;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.cvssScore = cvssScore;
        this.cve = cve;
        this.target = target;
        this.discoveredAt = discoveredAt;
        this.fingerprint = fingerprint;
        this.asset = asset;
        this.status = status;
    }
}
