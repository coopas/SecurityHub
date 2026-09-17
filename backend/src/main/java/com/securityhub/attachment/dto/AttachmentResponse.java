package com.securityhub.attachment.dto;

import com.securityhub.user.dto.UserSummary;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AttachmentResponse {

    private final Long id;
    private final Long vulnerabilityId;
    /** The sanitized name the client sent; the name on disk is never exposed. */
    private final String filename;
    private final String contentType;
    private final long sizeBytes;
    private final String checksumSha256;
    private final UserSummary uploadedBy;
    /**
     * Computed by the server, exactly like {@code editable} on a comment: the UI shows the
     * action only where the rule would allow it, and the backend re-checks it on every
     * delete anyway.
     */
    private final boolean canDelete;
    private final Instant createdAt;
}
