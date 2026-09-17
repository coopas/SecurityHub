package com.securityhub.project;

import com.securityhub.shared.repository.Specs;
import org.springframework.data.jpa.domain.Specification;

final class ProjectSpecifications {

    private ProjectSpecifications() {
    }

    static Specification<Project> filter(Long companyId, String search, ProjectStatus status) {
        return Specification.<Project>where(Specs.company(companyId))
                .and(Specs.eq("status", status))
                .and(Specs.containsAny(search, "name", "description"));
    }
}
