package com.securityhub.comment;

import com.securityhub.comment.dto.CommentResponse;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.User;
import com.securityhub.user.UserMapper;

/** Hand-written per ADR 0003; no MapStruct in this project. */
public final class CommentMapper {

    private CommentMapper() {
    }

    /**
     * Touches {@code author} and {@code vulnerability}, both lazy: callers must run inside
     * the service transaction because {@code open-in-view} is disabled.
     *
     * {@code editable} mirrors the server-side rule instead of being decided by the client:
     * the author or any ADMIN (docs/data-model.md). The backend re-checks it on every edit anyway.
     */
    public static CommentResponse toResponse(Comment comment, AuthenticatedUser current) {
        return new CommentResponse(comment.getId(), comment.getVulnerability().getId(),
                comment.getContent(), UserMapper.toSummary(comment.getAuthor()),
                canEdit(comment, current), comment.getCreatedAt(), comment.getUpdatedAt());
    }

    public static boolean canEdit(Comment comment, AuthenticatedUser current) {
        if (current == null) {
            return false;
        }
        if (current.isAdmin()) {
            return true;
        }
        User author = comment.getAuthor();
        return author != null && author.getId().equals(current.getId());
    }

    public static String normalizeContent(String content) {
        return content == null ? null : content.trim();
    }
}
