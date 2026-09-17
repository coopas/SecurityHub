package com.securityhub.company;

import com.securityhub.shared.model.BaseEntity;
import javax.persistence.Column;
import javax.persistence.Entity;
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
@Table(name = "companies")
public class Company extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 140, unique = true)
    private String slug;

    public Company(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }
}
