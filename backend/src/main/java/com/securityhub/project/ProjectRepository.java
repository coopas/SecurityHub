package com.securityhub.project;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method is scoped by company. There is deliberately no plain {@code findById}
 * wrapper in the service layer: a lookup that forgets the tenant is an IDOR waiting to
 * happen, so the company id is part of each signature.
 */
public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * {@code search} arrives already lowercased and wrapped in {@code %} by the service, so
     * the repository never has to guess how the caller wants the term interpreted.
     */
    @Query(value = "select p from Project p "
            + "left join fetch p.createdBy "
            + "where p.company.id = :companyId "
            + "and (:search is null or lower(p.name) like :search "
            + "or (p.description is not null and lower(p.description) like :search)) "
            + "and (:status is null or p.status = :status)",
            countQuery = "select count(p) from Project p "
            + "where p.company.id = :companyId "
            + "and (:search is null or lower(p.name) like :search "
            + "or (p.description is not null and lower(p.description) like :search)) "
            + "and (:status is null or p.status = :status)")
    Page<Project> search(@Param("companyId") Long companyId,
                         @Param("search") String search,
                         @Param("status") ProjectStatus status,
                         Pageable pageable);

    boolean existsByCompanyIdAndNameIgnoreCase(Long companyId, String name);

    /** Rename check: the project being renamed must not collide with itself. */
    boolean existsByCompanyIdAndNameIgnoreCaseAndIdNot(Long companyId, String name, Long id);

    long countByCompanyId(Long companyId);
}
