package com.securityhub.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "é obrigatório")
    @Size(max = 180)
    private String email;

    @NotBlank(message = "é obrigatório")
    @Size(max = 100)
    private String password;
}
