package com.securityhub.comment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Both finders carry the tenant and the parent vulnerability, so a comment id from another
 * company or from another discussion can never be reached by guessing.
 */
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** The listing renders the author, which would otherwise be one lazy proxy per row. */
    @EntityGraph(attributePaths = "author")
    Page<Comment> findByCompanyIdAndVulnerabilityId(Long companyId, Long vulnerabilityId, Pageable pageable);

    @EntityGraph(attributePaths = "author")
    Optional<Comment> findByIdAndCompanyIdAndVulnerabilityId(Long id, Long companyId, Long vulnerabilityId);

    long countByCompanyIdAndVulnerabilityId(Long companyId, Long vulnerabilityId);
}
