package com.securityhub.invitation.dto;

import com.securityhub.user.Role;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InvitationRequest {

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres")
    private String name;

    @NotBlank(message = "é obrigatório")
    @Email(message = "deve ser um e-mail válido")
    @Size(max = 180, message = "deve ter no máximo 180 caracteres")
    private String email;

    /** The company never comes from the body: it is the one of the authenticated principal. */
    @NotNull(message = "é obrigatório")
    private Role role;
}
