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
    /** Always 0 until the assets module supplies the real count. */
    private final long assetCount;
    private final String createdByName;
    private final Instant createdAt;
    private final Instant updatedAt;
}
