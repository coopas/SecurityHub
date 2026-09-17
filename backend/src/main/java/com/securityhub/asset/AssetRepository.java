package com.securityhub.asset;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The one lookup that returns the asset behind an identifier instead of only asserting
     * that one exists. Added for the scan importer, which has to resolve a finding's target to
     * the asset it will hang the vulnerability from.
     *
     * <p>Scoped by project and not by company, because that is the uniqueness boundary of
     * docs/data-model.md: the same identifier may legitimately name a different asset in a
     * different project, and a company-wide lookup would have no single answer. The caller
     * proves the project belongs to the tenant before asking.
     *
     * <p>{@code IgnoreCase} matches the {@code lower(identifier)} of the unique index of V4.
     * The index does not trim, so the caller normalizes the value first — otherwise
     * {@code "host "} reads as a different asset from {@code host}.
     */
    Optional<Asset> findByProjectIdAndIdentifierIgnoreCase(Long projectId, String identifier);

    /** Update check: the asset being edited must not collide with itself. */
    boolean existsByProjectIdAndIdentifierIgnoreCaseAndIdNot(Long projectId, String identifier, Long id);

    long countByProjectId(Long projectId);

    long countByCompanyId(Long companyId);

    /**
     * Child count behind the deletion rule of docs/permissions.md. The JPQL refers to the
     * Vulnerability entity by name, exactly like {@code ProjectRepository} reaches Asset, so
     * the assets package keeps no Java import of the vulnerabilities package and
     * {@code AssetService} keeps its existing collaborators.
     */
    @Query("select count(v.id) from Vulnerability v where v.asset.id = :assetId")
    long countVulnerabilitiesByAssetId(@Param("assetId") Long assetId);

    /**
     * One grouped count for a whole page instead of one query per row, which is what fills
     * {@code AssetResponse.vulnerabilityCount} without an N+1. Each row is
     * {@code [assetId, total]}; assets with no vulnerability simply do not come back.
     */
    @Query("select v.asset.id, count(v.id) from Vulnerability v "
            + "where v.company.id = :companyId and v.asset.id in :assetIds "
            + "group by v.asset.id")
    List<Object[]> countVulnerabilitiesByAsset(@Param("companyId") Long companyId,
                                               @Param("assetIds") Collection<Long> assetIds);
}
