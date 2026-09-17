package com.securityhub.user.dto;

import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Objeto e não primitivo: com {@code boolean}, um corpo sem o campo viraria {@code false} em
 * silêncio e desativaria alguém sem que ninguém tivesse pedido.
 */
@Getter
@Setter
public class ActiveChangeRequest {

    @NotNull(message = "é obrigatório")
    private Boolean active;
}
