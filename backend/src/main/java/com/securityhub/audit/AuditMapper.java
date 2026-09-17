package com.securityhub.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityhub.audit.dto.AuditLogResponse;
import java.util.Map;

public final class AuditMapper {

    private AuditMapper() {
    }

    public static AuditLogResponse toResponse(AuditLog log, ObjectMapper objectMapper) {
        return new AuditLogResponse(log.getId(), log.getActorId(), log.getActorEmail(), log.getAction(),
                log.getEntityType(), log.getEntityId(),
                readValues(log.getOldValueJson(), objectMapper),
                readValues(log.getNewValueJson(), objectMapper),
                log.getIpAddress(), log.getCreatedAt());
    }

    /**
     * Stored as text so the trail keeps whatever shape the entity had at the time. A row that
     * can no longer be parsed is surfaced as null rather than failing the whole page.
     */
    private static Map<String, Object> readValues(String json, ObjectMapper objectMapper) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            return null;
        }
    }
}
