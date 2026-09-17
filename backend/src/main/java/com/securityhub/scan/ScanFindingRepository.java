package com.securityhub.scan;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Like every other repository here, the tenant is part of each signature. */
public interface ScanFindingRepository extends JpaRepository<ScanImportFinding, Long> {

    /**
     * Every finding of one import, in insert order, which is report order. The asset is
     * fetched because the preview renders its name; the vulnerability is not, because the
     * payload needs only its id and reading an id off a proxy does not initialize it.
     *
     * <p>Not paged: the number of rows is bounded by {@code securityhub.scan.max-findings},
     * and the preview screen is the whole point of the staging area — a page of it would ask
     * the operator to map findings twenty at a time.
     */
    @EntityGraph(attributePaths = "asset")
    List<ScanImportFinding> findByCompanyIdAndScanImportIdOrderByIdAsc(Long companyId, Long importId);

    /**
     * The import id is part of the signature so a finding reached through the wrong import is
     * the same 404 as one that does not exist.
     */
    Optional<ScanImportFinding> findByIdAndCompanyIdAndScanImportId(Long id, Long companyId, Long importId);
}
