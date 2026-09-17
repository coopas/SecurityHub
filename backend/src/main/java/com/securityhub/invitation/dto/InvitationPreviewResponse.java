package com.securityhub.invitation.dto;

import com.securityhub.user.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Prévia pública: o mínimo para que a tela de aceite diga a quem está sendo convidado, para
 * qual empresa e com qual papel. Quem a obtém já provou a posse do token do e-mail.
 */
@Getter
@AllArgsConstructor
public class InvitationPreviewResponse {

    private final String name;
    private final String email;
    private final String companyName;
    private final Role role;
}
