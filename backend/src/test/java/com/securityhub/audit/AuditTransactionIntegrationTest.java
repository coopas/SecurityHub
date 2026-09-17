package com.securityhub.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.support.AbstractIntegrationTest;
import com.securityhub.support.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two propagation semantics of {@link AuditService} are the part of the audit trail that
 * a unit test cannot reach and that a past bug already broke once (commit {@code 00652a3}:
 * an independent transaction could not see the rows the caller had not committed yet, so
 * auditing a registration violated the foreign keys and surfaced as a 500).
 *
 * The boundary is crossed through a Spring proxy on purpose. {@code @Transactional} is
 * implemented by an interceptor around the bean, so calling such a method on {@code this} —
 * or on a bean whose method is not {@code public} — silently runs with no transaction
 * semantics at all and a test written that way would prove nothing. Hence the helper bean
 * below and the deliberate absence of {@code @Transactional} on the test methods themselves:
 * each one must observe what is actually committed.
 */
class AuditTransactionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TransactionalAuditCaller caller;

    private TestDataFactory.Tenant acme;

    @BeforeEach
    void createTenant() {
        acme = fixtures.tenant("acme");
    }

    /**
     * {@code record} joins the caller (REQUIRED), so the trail commits or rolls back with the
     * change it describes. A log line surviving a rolled-back mutation would describe
     * something that never happened.
     */
    @Test
    void recordRollsBackWithTheCaller() {
        assertThatThrownBy(() -> caller.recordThenFail(entry(AuditAction.CREATE)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditLogRepository.count()).isZero();
    }

    /**
     * {@code recordIndependently} commits on its own (REQUIRES_NEW), which is what keeps a
     * failed login in the trail even though the login transaction rolls back.
     */
    @Test
    void recordIndependentlySurvivesTheCallersRollback() {
        assertThatThrownBy(() -> caller.recordIndependentlyThenFail(entry(AuditAction.LOGIN_FAILED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditLogRepository.count()).isEqualTo(1L);
        assertThat(auditLogRepository.findAll().get(0).getAction()).isEqualTo(AuditAction.LOGIN_FAILED);
    }

    /**
     * Losing an audit line must never turn a valid request into a 500. A company id that does
     * not exist violates the foreign key of {@code audit_logs}; the failure has to stay inside
     * the independent transaction and reach the caller as nothing at all.
     */
    @Test
    void recordIndependentlySwallowsItsOwnFailureInsteadOfPoisoningTheCaller() {
        AuditEntry orphan = AuditEntry.ofActor(999_999L, null, "ghost@nowhere.test",
                AuditAction.LOGIN_FAILED, "User", null);

        assertThatCode(() -> caller.recordIndependentlyOnly(orphan)).doesNotThrowAnyException();

        assertThat(auditLogRepository.count()).isZero();
    }

    private AuditEntry entry(AuditAction action) {
        return AuditEntry.ofActor(acme.company.getId(), acme.admin.getId(), acme.admin.getEmail(),
                action, "User", acme.admin.getId());
    }

    @TestConfiguration
    static class TransactionalAuditCallerConfiguration {

        @Bean
        TransactionalAuditCaller transactionalAuditCaller(AuditService auditService) {
            return new TransactionalAuditCaller(auditService);
        }
    }

    /**
     * Stands in for a domain service. Every method is {@code public} because Spring's
     * transaction attribute source ignores {@code @Transactional} on non-public methods, so a
     * package-private helper here would open no transaction and make all three tests vacuous.
     */
    static class TransactionalAuditCaller {

        private final AuditService auditService;

        TransactionalAuditCaller(AuditService auditService) {
            this.auditService = auditService;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void recordThenFail(AuditEntry entry) {
            auditService.record(entry);
            throw new IllegalStateException("falha simulada depois de auditar");
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void recordIndependentlyThenFail(AuditEntry entry) {
            auditService.recordIndependently(entry);
            throw new IllegalStateException("falha simulada depois de auditar");
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public void recordIndependentlyOnly(AuditEntry entry) {
            auditService.recordIndependently(entry);
        }
    }
}
