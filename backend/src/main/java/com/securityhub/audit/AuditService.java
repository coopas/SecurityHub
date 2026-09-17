package com.securityhub.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final int MAX_JSON_LENGTH = 8000;

    private final AuditLogWriter writer;
    private final ObjectMapper objectMapper;

    /**
     * Joins the caller's transaction, which is required whenever the audited rows are being
     * created by that same transaction: an independent transaction cannot see them yet and
     * the foreign keys would fail. It also makes the change and its trail commit or roll back
     * together, so the log never describes something that did not happen.
     */
    public void record(AuditEntry entry) {
        writer.writeInCurrentTransaction(toLog(entry));
    }

    /**
     * Commits on its own so the event survives a rollback of the surrounding operation —
     * used for authentication outcomes, notably a failed login. Only safe when every row it
     * references is already committed. A failure here is logged and swallowed: losing an
     * audit line must not turn a valid request into a 500.
     */
    public void recordIndependently(AuditEntry entry) {
        try {
            writer.writeInNewTransaction(toLog(entry));
        } catch (RuntimeException ex) {
            log.error("Falha ao registrar auditoria de {} em {}",
                    entry.getAction(), entry.getEntityType(), ex);
        }
    }

    private AuditLog toLog(AuditEntry entry) {
        AuditLog auditLog = new AuditLog();
        auditLog.setCompanyId(entry.getCompanyId());
        auditLog.setActorId(entry.getActorId());
        auditLog.setActorEmail(entry.getActorEmail());
        auditLog.setAction(entry.getAction());
        auditLog.setEntityType(entry.getEntityType());
        auditLog.setEntityId(entry.getEntityId());
        auditLog.setOldValueJson(toJson(entry.getOldValue()));
        auditLog.setNewValueJson(toJson(entry.getNewValue()));
        auditLog.setIpAddress(currentIpAddress());
        auditLog.setCreatedAt(Instant.now());
        return auditLog;
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
