package com.securityhub.invitation.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Nome, e-mail, papel e empresa vêm do convite, não daqui: são exatamente o que quem convidou
 * decidiu, e aceitá-los é o que o clique no link significa. O convidado só escolhe a senha.
 *
 * A restrição de senha é a de {@code RegisterRequest}, repetida literalmente pelo mesmo motivo
 * descrito em {@code PasswordResetConfirmRequest}.
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
