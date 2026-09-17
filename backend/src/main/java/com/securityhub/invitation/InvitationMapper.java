package com.securityhub.invitation;

import com.securityhub.invitation.dto.InvitationPreviewResponse;
import com.securityhub.invitation.dto.InvitationResponse;

/** Hand-written, like every mapper in the project (ADR 0003). */
public final class InvitationMapper {

    private InvitationMapper() {
    }

    public static InvitationResponse toResponse(Invitation invitation) {
        return new InvitationResponse(invitation.getId(), invitation.getName(), invitation.getEmail(),
                invitation.getRole(), invitation.getStatus(), invitation.getExpiresAt(),
                invitation.getAcceptedAt(), invitation.getInvitedBy().getId(),
                invitation.getInvitedBy().getName(), invitation.getCreatedAt());
    }

    public static InvitationPreviewResponse toPreview(Invitation invitation) {
        return new InvitationPreviewResponse(invitation.getName(), invitation.getEmail(),
                invitation.getCompany().getName(), invitation.getRole());
    }
}
