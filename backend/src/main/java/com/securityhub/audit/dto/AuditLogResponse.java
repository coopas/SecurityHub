package com.securityhub.audit.dto;

import com.securityhub.audit.AuditAction;
import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuditLogResponse {

    private final Long id;
    private final Long actorId;
    private final String actorEmail;
    private final AuditAction action;
    private final String entityType;
    private final Long entityId;
    private final Map<String, Object> oldValue;
    private final Map<String, Object> newValue;
    private final String ipAddress;
    private final Instant createdAt;
}
