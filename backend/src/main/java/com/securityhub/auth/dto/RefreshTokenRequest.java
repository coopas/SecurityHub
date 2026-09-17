package com.securityhub.auth.dto;

import javax.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Body of {@code /auth/refresh} and of {@code /auth/logout}. A single DTO for both: the logout
 * ends the family of the presented token, so it takes exactly the same input.
 *
 * No {@code @Size}: the token is opaque and its length is a detail of the issuing, not of the
 * contract.
 */
@Getter
@Setter
public class RefreshTokenRequest {

    @NotBlank(message = "é obrigatório")
    private String refreshToken;
}
