package com.securityhub.attachment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Every lookup carries the tenant in its signature, like the rest of the repositories of the
 * project: a finder that forgets the company is an IDOR waiting to happen.
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * The uploader is rendered in the payload and decides {@code canDelete}, so it is fetched
     * instead of being one lazy proxy per row.
     */
    @EntityGraph(attributePaths = {"uploadedBy"})
    List<Attachment> findByCompanyIdAndVulnerabilityIdOrderByCreatedAtAscIdAsc(
            Long companyId, Long vulnerabilityId, Pageable pageable);

    long countByCompanyIdAndVulnerabilityId(Long companyId, Long vulnerabilityId);

    @EntityGraph(attributePaths = {"uploadedBy"})
    Optional<Attachment> findByIdAndCompanyIdAndVulnerabilityId(Long id, Long companyId,
                                                                Long vulnerabilityId);
}
