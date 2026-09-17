package com.securityhub.project.dto;

import com.securityhub.project.ProjectStatus;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ProjectResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final ProjectStatus status;
    /** Resolved by one grouped count per page, never by initializing the collection. */
    private final long assetCount;
    private final String createdByName;
    private final Instant createdAt;
    private final Instant updatedAt;
}
