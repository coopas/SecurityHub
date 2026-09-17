package com.securityhub.comment.dto;

import com.securityhub.user.dto.UserSummary;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CommentResponse {

    private final Long id;
    private final Long vulnerabilityId;
    private final String content;
    private final UserSummary author;
    /** Lets the UI show "editar" only where the rule of docs/data-model.md would actually allow it. */
    private final boolean editable;
    private final Instant createdAt;
    private final Instant updatedAt;
}
