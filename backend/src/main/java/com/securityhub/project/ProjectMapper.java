package com.securityhub.project;

import com.securityhub.project.dto.ProjectRequest;
import com.securityhub.project.dto.ProjectResponse;
import com.securityhub.user.User;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class ProjectMapper {

    private ProjectMapper() {
    }

    /**
     * The asset count is passed in rather than read from the entity: a mapped collection
     * would make every listed row initialize its assets, so the service supplies the value
     * from a single grouped count query.
     */
    public static ProjectResponse toResponse(Project project, long assetCount) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                project.getStatus(), assetCount, createdByName(project.getCreatedBy()),
                project.getCreatedAt(), project.getUpdatedAt());
    }

    /** Applies the mutable part of the request; company and author are never client-supplied. */
    public static void applyUpdate(Project project, ProjectRequest request) {
        project.setName(normalizeName(request.getName()));
        project.setDescription(normalizeDescription(request.getDescription()));
        if (request.getStatus() != null) {
            project.setStatus(request.getStatus());
        }
    }

    public static String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    public static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String createdByName(User user) {
        return user == null ? null : user.getName();
    }
}
