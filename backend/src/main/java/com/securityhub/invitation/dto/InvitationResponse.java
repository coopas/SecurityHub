package com.securityhub.invitation.dto;

import com.securityhub.invitation.InvitationStatus;
import com.securityhub.user.Role;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Never carries the token, nor its hash. Whoever administers the company does not need the
 * secret to manage the invitation, and returning it would turn the listing into a way of taking
 * over the account of any invitee.
 */
@Getter
@AllArgsConstructor
public class InvitationResponse {

    private final Long id;
    private final String name;
    private final String email;
    private final Role role;
    private final InvitationStatus status;
    private final Instant expiresAt;
    private final Instant acceptedAt;
    private final Long invitedById;
    private final String invitedByName;
    private final Instant createdAt;
}
