package com.securityhub.attachment;

import com.securityhub.company.Company;
import com.securityhub.shared.model.BaseEntity;
import com.securityhub.user.User;
import com.securityhub.vulnerability.Vulnerability;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

@Getter
@Setter
@Entity
@NoArgsConstructor
@BatchSize(size = 50)
@Table(name = "vulnerability_attachments")
public class Attachment extends BaseEntity {

    /** Denormalized tenant, always equal to {@code vulnerability.company}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vulnerability_id", nullable = false, updatable = false)
    private Vulnerability vulnerability;

    /** Nullable: removing a user must not remove the evidence they attached. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by", updatable = false)
    private User uploadedBy;

    /** The sanitized client name. Announced on download, never used to build a path. */
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 32, updatable = false)
    private String storedFilename;

    /** Sniffed from the bytes, never copied from the request header. */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * {@code columnDefinition} names the PostgreSQL internal type of {@code CHAR(64)} rather
     * than the SQL spelling: Hibernate's schema validation compares what the driver reports
     * for the column, and the driver reports {@code bpchar}. Without it, {@code ddl-auto:
     * validate} refuses to start against the table V8 creates.
     */
    @Column(name = "checksum_sha256", nullable = false, length = 64,
            updatable = false, columnDefinition = "bpchar(64)")
    private String checksumSha256;

    public Attachment(Company company, Vulnerability vulnerability, User uploadedBy,
                      String originalFilename, String storedFilename, String contentType,
                      long sizeBytes, String checksumSha256) {
        this.company = company;
        this.vulnerability = vulnerability;
        this.uploadedBy = uploadedBy;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
    }
}
