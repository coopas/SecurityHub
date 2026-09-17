package com.securityhub.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so the REQUIRES_NEW boundary is crossed through a proxy and its commit
 * failure can be caught by {@link AuditService} instead of poisoning the caller.
 *
 * Both methods must stay {@code public}. Spring's {@code AnnotationTransactionAttributeSource}
 * only considers public methods, so a package-private one carries no transaction attribute at
 * all: {@code @Transactional} is then silently ignored and REQUIRES_NEW degrades into "join
 * whatever the caller had". A failed login would leave no trail, and a foreign key violation
 * inside the trail would mark the caller's transaction rollback-only and turn a valid request
 * into a 500. {@code AuditTransactionIntegrationTest} pins all three behaviours.
 */
@Component
@RequiredArgsConstructor
class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInNewTransaction(AuditLog log) {
        auditLogRepository.saveAndFlush(log);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void writeInCurrentTransaction(AuditLog log) {
        auditLogRepository.save(log);
    }
}
