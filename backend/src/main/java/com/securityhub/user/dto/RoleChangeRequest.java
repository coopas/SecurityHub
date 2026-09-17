package com.securityhub.user.dto;

import com.securityhub.user.Role;
import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleChangeRequest {

    @NotNull(message = "é obrigatório")
    private Role role;
}
