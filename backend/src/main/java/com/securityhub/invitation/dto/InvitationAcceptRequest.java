package com.securityhub.invitation.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Name, e-mail, role and company come from the invitation, not from here: they are exactly what
 * whoever invited decided, and accepting them is what clicking the link means. The invitee only
 * chooses the password.
 *
 * The password constraint is the one from {@code RegisterRequest}, repeated verbatim for the
 * same reason described in {@code PasswordResetConfirmRequest}.
 */
@Getter
@Setter
public class InvitationAcceptRequest {

    @NotBlank(message = "é obrigatório")
    private String token;

    @NotBlank(message = "é obrigatório")
    @Size(min = 10, max = 100, message = "deve ter entre 10 e 100 caracteres")
    private String password;
}
