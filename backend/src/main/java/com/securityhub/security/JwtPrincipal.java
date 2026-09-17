package com.securityhub.security;

import com.securityhub.user.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class JwtPrincipal {

    private final Long userId;
    private final Long companyId;
    private final Role role;
}
