package com.securityhub.user.dto;

import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * An object and not a primitive: with {@code boolean}, a body without the field would silently
 * become {@code false} and deactivate somebody nobody had asked to deactivate.
 */
@Getter
@Setter
public class ActiveChangeRequest {

    @NotNull(message = "é obrigatório")
    private Boolean active;
}
