package com.securityhub.audit;

import com.securityhub.security.AuthenticatedUser;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * Describes one auditable fact. Built with the static factories so callers cannot forget
 * the company scope, which is what keeps the trail readable by the right tenant only.
 */
@Getter
public final class AuditEntry {

    private final Long companyId;
    private final Long actorId;
    private final String actorEmail;
    private final AuditAction action;
    private final String entityType;
    private final Long entityId;
    private final Map<String, Object> oldValue;
    private final Map<String, Object> newValue;

    private AuditEntry(Long companyId, Long actorId, String actorEmail, AuditAction action,
                       String entityType, Long entityId,
                       Map<String, Object> oldValue, Map<String, Object> newValue) {
        this.companyId = companyId;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public static AuditEntry by(AuthenticatedUser actor, AuditAction action, String entityType, Long entityId) {
        return new AuditEntry(actor.getCompanyId(), actor.getId(), actor.getEmail(), action,
                entityType, entityId, null, null);
    }

    public static AuditEntry created(AuthenticatedUser actor, String entityType, Long entityId,
                                     Map<String, Object> newValue) {
        return new AuditEntry(actor.getCompanyId(), actor.getId(), actor.getEmail(), AuditAction.CREATE,
                entityType, entityId, null, newValue);
    }

    public static AuditEntry updated(AuthenticatedUser actor, String entityType, Long entityId,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        return new AuditEntry(actor.getCompanyId(), actor.getId(), actor.getEmail(), AuditAction.UPDATE,
                entityType, entityId, oldValue, newValue);
    }

    public static AuditEntry changed(AuthenticatedUser actor, AuditAction action, String entityType, Long entityId,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        return new AuditEntry(actor.getCompanyId(), actor.getId(), actor.getEmail(), action,
                entityType, entityId, oldValue, newValue);
    }

    public static AuditEntry deleted(AuthenticatedUser actor, String entityType, Long entityId,
                                     Map<String, Object> oldValue) {
        return new AuditEntry(actor.getCompanyId(), actor.getId(), actor.getEmail(), AuditAction.DELETE,
                entityType, entityId, oldValue, null);
    }

    /** For authentication events, where there is no AuthenticatedUser yet. */
    public static AuditEntry ofActor(Long companyId, Long actorId, String actorEmail, AuditAction action,
                                     String entityType, Long entityId) {
        return new AuditEntry(companyId, actorId, actorEmail, action, entityType, entityId, null, null);
    }

    public static Map<String, Object> values() {
        return new LinkedHashMap<>();
    }
}
