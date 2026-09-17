package com.securityhub.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityhub.audit.dto.AuditLogResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.web.PageableSupport;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final Set<String> SORTABLE = new HashSet<>(Arrays.asList("createdAt", "action", "entityType"));
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<AuditLogResponse> search(AuthenticatedUser current, String entityType, Long actorId,
                                         AuditAction action, Instant from, Instant to, Pageable pageable) {
        Pageable sanitized = PageableSupport.sanitize(pageable, SORTABLE, DEFAULT_SORT);
        String normalizedEntityType = (entityType == null || entityType.trim().isEmpty())
                ? null : entityType.trim();
        return auditLogRepository
                .findAll(AuditSpecifications.filter(current.getCompanyId(), normalizedEntityType,
                        actorId, action, from, to), sanitized)
                .map(log -> AuditMapper.toResponse(log, objectMapper));
    }
}
