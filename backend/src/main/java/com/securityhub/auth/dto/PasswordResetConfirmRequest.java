package com.securityhub.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A restrição de senha é a de {@link RegisterRequest}, repetida literalmente. Uma anotação
 * compartilhada seria menos duplicação e mais acoplamento: a regra de senha do cadastro e a da
 * redefinição são a mesma hoje por coincidência de política, e a que mudar primeiro não deve
 * arrastar a outra sem que alguém decida isso.
 */
@Getter
@Setter
public class PasswordResetConfirmRequest {

    @NotBlank(message = "é obrigatório")
    private String token;

    @NotBlank(message = "é obrigatório")
    @Size(min = 10, max = 100, message = "deve ter entre 10 e 100 caracteres")
    private String password;
}
