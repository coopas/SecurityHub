package com.securityhub.auth.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres")
    private String companyName;

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres")
    private String name;

    @NotBlank(message = "é obrigatório")
    @Email(message = "deve ser um e-mail válido")
    @Size(max = 180, message = "deve ter no máximo 180 caracteres")
    private String email;

    @NotBlank(message = "é obrigatório")
    @Size(min = 10, max = 100, message = "deve ter entre 10 e 100 caracteres")
    private String password;
}
