package com.securityhub.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordResetRequest {

    @NotBlank(message = "é obrigatório")
    @Email(message = "deve ser um e-mail válido")
    @Size(max = 180, message = "deve ter no máximo 180 caracteres")
    private String email;
}
