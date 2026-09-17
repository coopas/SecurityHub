package com.securityhub.user;

import com.securityhub.shared.repository.Specs;
import org.springframework.data.jpa.domain.Specification;

final class UserSpecifications {

    private UserSpecifications() {
    }

    static Specification<User> filter(Long companyId, Role role, Boolean active, String search) {
        return Specification.<User>where(Specs.company(companyId))
                .and(Specs.eq("role", role))
                .and(Specs.eq("active", active))
                .and(Specs.containsAny(search, "name", "email"));
    }
}
