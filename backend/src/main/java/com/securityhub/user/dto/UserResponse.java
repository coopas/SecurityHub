package com.securityhub.user.dto;

import com.securityhub.user.Role;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserResponse {

    private final Long id;
    private final String name;
    private final String email;
    private final Role role;
    private final boolean active;
    private final Long companyId;
    private final String companyName;
    private final Instant lastLoginAt;
    private final Instant createdAt;
}
