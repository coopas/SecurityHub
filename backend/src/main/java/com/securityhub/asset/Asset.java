package com.securityhub.asset;

import com.securityhub.company.Company;
import com.securityhub.project.Project;
import com.securityhub.shared.model.BaseEntity;
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

@Getter
@Setter
@Entity
@NoArgsConstructor
@BatchSize(size = 50)
@Table(name = "assets")
public class Asset extends BaseEntity {

    /**
     * Denormalized tenant, always equal to {@code project.company}. Keeping it on the row
     * lets every filter and every uniqueness check stay a single-table query.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    /** Mutable: an asset may be moved to another project of the same company. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssetType type;

    /** URL, hostname, IP or any other locator; unique inside the project when filled. */
    @Column(length = 255)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Environment environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Criticality criticality;

    public Asset(Company company, Project project, String name, String description, AssetType type,
                 String identifier, Environment environment, Criticality criticality) {
        this.company = company;
        this.project = project;
        this.name = name;
        this.description = description;
        this.type = type;
        this.identifier = identifier;
        this.environment = environment;
        this.criticality = criticality;
    }
}
