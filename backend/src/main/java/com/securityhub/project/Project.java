package com.securityhub.project;

import com.securityhub.company.Company;
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

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "projects")
public class Project extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    /** Nullable: the author may be removed from the company without taking the project with them. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    public Project(Company company, String name, String description, ProjectStatus status, User createdBy) {
        this.company = company;
        this.name = name;
        this.description = description;
        this.status = status == null ? ProjectStatus.ACTIVE : status;
        this.createdBy = createdBy;
    }
}
