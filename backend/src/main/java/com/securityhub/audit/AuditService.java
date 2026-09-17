package com.securityhub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_JSON_LENGTH = 8000;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Runs in its own transaction so the trail survives a rollback of the business
     * operation — a failed attempt is exactly what an auditor needs to see.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry) {
        try {
            AuditLog log = new AuditLog();
            log.setCompanyId(entry.getCompanyId());
            log.setActorId(entry.getActorId());
            log.setActorEmail(entry.getActorEmail());
            log.setAction(entry.getAction());
            log.setEntityType(entry.getEntityType());
            log.setEntityId(entry.getEntityId());
            log.setOldValueJson(toJson(entry.getOldValue()));
            log.setNewValueJson(toJson(entry.getNewValue()));
            log.setIpAddress(currentIpAddress());
            log.setCreatedAt(Instant.now());
            auditLogRepository.save(log);
        } catch (RuntimeException ex) {
            // Auditing must never take down the operation it is describing.
            log.error("Falha ao registrar auditoria de {} em {}", entry.getAction(), entry.getEntityType(), ex);
        }
    }

    private String toJson(Map<String, Object> values) {
        Map<String, Object> sanitized = AuditSanitizer.sanitize(values);
        if (sanitized == null || sanitized.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(sanitized);
            return json.length() > MAX_JSON_LENGTH ? json.substring(0, MAX_JSON_LENGTH) : json;
        } catch (JsonProcessingException ex) {
            log.warn("Não foi possível serializar valores de auditoria", ex);
            return null;
        }
    }

    private String currentIpAddress() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.length() <= 45 ? remote : remote.substring(0, 45);
    }
}
