package com.securityhub.asset;

import com.securityhub.asset.dto.AssetRequest;
import com.securityhub.asset.dto.AssetResponse;
import com.securityhub.project.Project;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class AssetMapper {

    private AssetMapper() {
    }

    /**
     * Touches {@code asset.project}, which is lazy: callers must run inside the service
     * transaction because {@code open-in-view} is disabled.
     *
     * The vulnerability count is passed in rather than read from the entity: a mapped
     * collection would make every listed row initialize its vulnerabilities, so the service
     * supplies the value from a single grouped count query, mirroring what
     * {@code ProjectMapper} does with the asset count.
     */
    public static AssetResponse toResponse(Asset asset, long vulnerabilityCount) {
        Project project = asset.getProject();
        return new AssetResponse(asset.getId(), asset.getName(), asset.getDescription(), asset.getType(),
                asset.getIdentifier(), asset.getEnvironment(), asset.getCriticality(),
                project == null ? null : project.getId(), project == null ? null : project.getName(),
                vulnerabilityCount, asset.getCreatedAt(), asset.getUpdatedAt());
    }

    /**
     * Applies the mutable part of the request. The company is never client-supplied and the
     * project is resolved by the service, which is the only place that can prove the target
     * project belongs to the caller's tenant.
     */
    public static void applyUpdate(Asset asset, AssetRequest request) {
        asset.setName(normalizeName(request.getName()));
        asset.setDescription(normalizeDescription(request.getDescription()));
        asset.setType(request.getType());
        asset.setIdentifier(normalizeIdentifier(request.getIdentifier()));
        asset.setEnvironment(request.getEnvironment());
        asset.setCriticality(request.getCriticality());
    }

    public static String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    public static String normalizeDescription(String description) {
        return blankToNull(description);
    }

    /**
     * A blank identifier means "no identifier": it must become {@code null} so the partial
     * unique index ignores the row instead of colliding with every other blank one.
     */
    public static String normalizeIdentifier(String identifier) {
        return blankToNull(identifier);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
