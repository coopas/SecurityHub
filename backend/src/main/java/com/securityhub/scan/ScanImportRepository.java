package com.securityhub.scan;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every lookup carries the tenant in its signature, like {@code ProjectRepository} and
 * {@code AssetRepository}: a finder that forgets the company is an IDOR waiting to happen.
 */
public interface ScanImportRepository extends JpaRepository<ScanImport, Long> {

    /** The payload renders the project name and the importer's name, both lazy to-ones. */
    @EntityGraph(attributePaths = {"project", "importedBy"})
    Optional<ScanImport> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * The history listing. Only to-one associations are fetched, so the graph does not turn
     * the paged query into in-memory pagination.
     */
    @EntityGraph(attributePaths = {"project", "importedBy"})
    Page<ScanImport> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * The fingerprints of this company's backlog that the report just uploaded also contains —
     * one query for the whole report instead of one existence check per finding.
     *
     * <p>The JPQL names the Vulnerability entity rather than importing a repository of that
     * package, exactly like {@code AssetRepository} reaches Vulnerability and
     * {@code ProjectRepository} reaches Asset.
     *
     * <p>Scoped by company because the fingerprint is only unique inside one: two tenants
     * scanning the same public host produce the same hash, and neither may learn that the
     * other did.
     */
    @Query("select v.fingerprint from Vulnerability v "
            + "where v.company.id = :companyId and v.fingerprint in :fingerprints")
    List<String> findExistingFingerprints(@Param("companyId") Long companyId,
                                          @Param("fingerprints") Collection<String> fingerprints);
}
