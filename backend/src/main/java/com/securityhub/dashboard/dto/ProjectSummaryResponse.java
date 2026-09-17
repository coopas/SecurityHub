package com.securityhub.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One line of {@code summary.topProjects}. */
@Getter
@AllArgsConstructor
public class ProjectSummaryResponse {

    private final Long projectId;
    private final String projectName;
    private final long total;
    /** OPEN + IN_PROGRESS inside this project. */
    private final long open;
    private final long overdue;
}
