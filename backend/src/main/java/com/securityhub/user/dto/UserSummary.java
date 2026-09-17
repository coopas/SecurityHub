package com.securityhub.user.dto;

import com.securityhub.user.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Lightweight projection used wherever a user is referenced from another aggregate. */
@Getter
@AllArgsConstructor
public class UserSummary {

    private final Long id;
    private final String name;
    private final String email;
    private final Role role;
}
