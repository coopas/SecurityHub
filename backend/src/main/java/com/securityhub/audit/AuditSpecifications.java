package com.securityhub.audit;

import com.securityhub.shared.repository.Specs;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

final class AuditSpecifications {

    private AuditSpecifications() {
    }

    static Specification<AuditLog> filter(Long companyId, String entityType, Long actorId,
                                          AuditAction action, Instant from, Instant to) {
        return Specification.<AuditLog>where(Specs.companyColumn(companyId))
                .and(Specs.eq("entityType", entityType))
                .and(Specs.eq("actorId", actorId))
                .and(Specs.eq("action", action))
                .and(Specs.from("createdAt", from))
                .and(Specs.to("createdAt", to));
    }
}
