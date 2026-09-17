package com.securityhub.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so the REQUIRES_NEW boundary is crossed through a proxy and its commit
 * failure can be caught by {@link AuditService} instead of poisoning the caller.
 */
@Component
@RequiredArgsConstructor
class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void writeInNewTransaction(AuditLog log) {
        auditLogRepository.saveAndFlush(log);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    void writeInCurrentTransaction(AuditLog log) {
        auditLogRepository.save(log);
    }
}
