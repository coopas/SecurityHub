package com.securityhub.auth.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Corpo de {@code /auth/refresh} e de {@code /auth/logout}. Um DTO só para os dois: o logout
 * encerra a família do token apresentado, então recebe exatamente a mesma entrada.
 *
 * Sem {@code @Size}: o token é opaco e o seu tamanho é detalhe da emissão, não do contrato.
 */
@Getter
@Setter
public class RefreshTokenRequest {

    @NotBlank(message = "é obrigatório")
    private String refreshToken;
}
