package com.securityhub.invitation.dto;

import com.securityhub.invitation.InvitationStatus;
import com.securityhub.user.Role;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Nunca carrega o token, nem o seu hash. Quem administra a empresa não precisa do segredo para
 * gerenciar o convite, e devolvê-lo transformaria a listagem em uma forma de assumir a conta
 * de qualquer convidado.
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
