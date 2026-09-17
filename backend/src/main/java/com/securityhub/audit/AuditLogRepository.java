package com.securityhub.audit;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read and append only. {@code JpaRepository} still exposes delete methods, which is why the
 * API layer never receives this bean and no controller maps a mutating audit endpoint.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select a from AuditLog a where a.companyId = :companyId "
            + "and (:entityType is null or a.entityType = :entityType) "
            + "and (:actorId is null or a.actorId = :actorId) "
            + "and (:action is null or a.action = :action) "
            + "and (:from is null or a.createdAt >= :from) "
            + "and (:to is null or a.createdAt <= :to)")
    Page<AuditLog> search(@Param("companyId") Long companyId,
                          @Param("entityType") String entityType,
                          @Param("actorId") Long actorId,
                          @Param("action") AuditAction action,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
