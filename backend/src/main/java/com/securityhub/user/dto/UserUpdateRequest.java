package com.securityhub.user.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Só o nome, e é uma decisão de segurança, não uma lacuna.
 *
 * O e-mail é ao mesmo tempo o identificador de login e o canal de recuperação de senha. Um
 * ADMIN capaz de repontar o e-mail de outra pessoa poderia mandar o link de redefinição dela
 * para um endereço próprio e assumir a conta — e a trilha registraria apenas "usuário
 * atualizado". Trocar de e-mail, se um dia for preciso, é um fluxo com confirmação nos dois
 * endereços, não um campo neste DTO. {@code UserUpdateRequestTest} prende a ausência.
 *
 * Papel e situação também não estão aqui: têm endpoint próprio porque cada um carrega guardas
 * que uma atualização genérica esconderia.
 */
@Getter
@Setter
public class UserUpdateRequest {

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres")
    private String name;
}
