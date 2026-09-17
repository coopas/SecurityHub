package com.securityhub.invitation.dto;

import com.securityhub.user.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Public preview: the minimum for the acceptance screen to say who is being invited, to which
 * company and with which role. Whoever obtains it has already proved possession of the e-mail
 * token.
 */
@Getter
@AllArgsConstructor
public class InvitationPreviewResponse {

    private final String name;
    private final String email;
    private final String companyName;
    private final Role role;
}
