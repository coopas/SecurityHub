package com.securityhub.comment;

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
@Table(name = "comments")
public class Comment extends BaseEntity {

    /** Denormalized tenant, always equal to {@code vulnerability.company}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vulnerability_id", nullable = false, updatable = false)
    private Vulnerability vulnerability;

    /** Never reassigned: the author is who may edit it, together with any ADMIN. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    @Column(nullable = false, length = 2000)
    private String content;

    public Comment(Company company, Vulnerability vulnerability, User author, String content) {
        this.company = company;
        this.vulnerability = vulnerability;
        this.author = author;
        this.content = content;
    }
}
