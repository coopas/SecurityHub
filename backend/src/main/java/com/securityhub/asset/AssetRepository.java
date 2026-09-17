package com.securityhub.asset;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Like {@code ProjectRepository}, every lookup carries the tenant in its signature so a
 * forgotten company filter cannot turn into an IDOR. The identifier checks are scoped by
 * project because that is the uniqueness boundary of docs/data-model.md.
 */
public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    /**
     * The listing renders the project name, which would otherwise initialize one lazy
     * proxy per row. The graph is declared on the specification query because that is the
     * only paged read of this repository.
     */
    @Override
    @EntityGraph(attributePaths = "project")
    Page<Asset> findAll(Specification<Asset> spec, Pageable pageable);

    Optional<Asset> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByProjectIdAndIdentifierIgnoreCase(Long projectId, String identifier);

    /** Update check: the asset being edited must not collide with itself. */
    boolean existsByProjectIdAndIdentifierIgnoreCaseAndIdNot(Long projectId, String identifier, Long id);

    long countByProjectId(Long projectId);

    long countByCompanyId(Long companyId);
}
