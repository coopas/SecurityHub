package com.securityhub.project;

import com.securityhub.project.dto.ProjectRequest;
import com.securityhub.project.dto.ProjectResponse;
import com.securityhub.user.User;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class ProjectMapper {

    /**
     * The assets table does not exist yet. The field is part of the contract from
     * the start so the Angular list does not have to change shape later; the assets module
     * replaces this constant with a real projection.
     */
    static final long ASSET_COUNT_PLACEHOLDER = 0L;

    private ProjectMapper() {
    }

    public static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                project.getStatus(), ASSET_COUNT_PLACEHOLDER, createdByName(project.getCreatedBy()),
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
