package com.securityhub.project;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Child count behind the deletion rule of docs/permissions.md. The JPQL refers to the
     * Asset entity by name, so the projects package keeps no Java dependency on the assets
     * package and the service keeps its existing collaborators.
     */
    @Query("select count(a.id) from Asset a where a.project.id = :projectId")
    long countAssetsByProjectId(@Param("projectId") Long projectId);

    /**
     * One grouped count for a whole page instead of one query per row, which is what fills
     * {@code ProjectResponse.assetCount} without an N+1. Each row is
     * {@code [projectId, total]}; projects with no asset simply do not come back.
     */
    @Query("select a.project.id, count(a.id) from Asset a "
            + "where a.company.id = :companyId and a.project.id in :projectIds "
            + "group by a.project.id")
    List<Object[]> countAssetsByProject(@Param("companyId") Long companyId,
                                        @Param("projectIds") Collection<Long> projectIds);
}
