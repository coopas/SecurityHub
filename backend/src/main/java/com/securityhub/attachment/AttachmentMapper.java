package com.securityhub.attachment;

import com.securityhub.attachment.dto.AttachmentResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserMapper;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class AttachmentMapper {

    private AttachmentMapper() {
    }

    /**
     * Touches {@code vulnerability} and {@code uploadedBy}, both lazy: callers must run inside
     * the service transaction because {@code open-in-view} is disabled.
     */
    public static AttachmentResponse toResponse(Attachment attachment, AuthenticatedUser current) {
        return new AttachmentResponse(attachment.getId(), attachment.getVulnerability().getId(),
                attachment.getOriginalFilename(), attachment.getContentType(),
                attachment.getSizeBytes(), attachment.getChecksumSha256(),
                UserMapper.toSummary(attachment.getUploadedBy()), canDelete(attachment, current),
                attachment.getCreatedAt());
    }

    /**
     * The uploader or any ADMIN, mirroring {@code CommentMapper.canEdit}. VIEWER is excluded
     * explicitly and not only by the annotation on the service: a VIEWER who uploaded nothing
     * would come out false anyway, but a VIEWER must never be shown an action the coarse gate
     * would refuse.
     */
    public static boolean canDelete(Attachment attachment, AuthenticatedUser current) {
        if (current == null || current.hasRole(Role.VIEWER)) {
            return false;
        }
        if (current.isAdmin()) {
            return true;
        }
        User uploader = attachment.getUploadedBy();
        return uploader != null && uploader.getId().equals(current.getId());
    }
}
