package com.securityhub.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Read and append only. {@code JpaRepository} still exposes delete methods, which is why the
 * API layer never receives this bean and no controller maps a mutating audit endpoint.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {
}
