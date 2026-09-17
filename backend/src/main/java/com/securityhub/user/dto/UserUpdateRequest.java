package com.securityhub.user.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * The name only, and that is a security decision, not a gap.
 *
 * The e-mail is at once the login identifier and the password recovery channel. An ADMIN able
 * to repoint somebody else's e-mail could send that person's reset link to an address of their
 * own and take over the account — and the trail would record only "user updated". Changing an
 * e-mail, if it is ever needed, is a flow with confirmation on both addresses, not a field in
 * this DTO. {@code UserUpdateRequestTest} pins the absence down.
 *
 * Role and status are not here either: each has its own endpoint because each carries guards
 * that a generic update would hide.
 */
@Getter
@Setter
public class UserUpdateRequest {

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 120, message = "deve ter entre 2 e 120 caracteres")
    private String name;
}
