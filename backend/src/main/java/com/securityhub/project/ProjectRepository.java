package com.securityhub.project;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Every method is scoped by company. There is deliberately no plain {@code findById}
 * wrapper in the service layer: a lookup that forgets the tenant is an IDOR waiting to
 * happen, so the company id is part of each signature.
 */
public interface ProjectRepository extends JpaRepository<Project, Long>,
        JpaSpecificationExecutor<Project> {

    Optional<Project> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByCompanyIdAndNameIgnoreCase(Long companyId, String name);

    /** Rename check: the project being renamed must not collide with itself. */
    boolean existsByCompanyIdAndNameIgnoreCaseAndIdNot(Long companyId, String name, Long id);

    long countByCompanyId(Long companyId);
}
