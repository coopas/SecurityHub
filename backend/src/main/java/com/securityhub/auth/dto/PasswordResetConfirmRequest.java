package com.securityhub.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The password constraint is the one of {@link RegisterRequest}, repeated literally. A shared
 * annotation would be less duplication and more coupling: the password rule of the registration
 * and the one of the reset are the same today by a coincidence of policy, and whichever changes
 * first must not drag the other along without somebody deciding so.
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
