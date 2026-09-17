package com.securityhub.user;

import com.securityhub.user.dto.UserResponse;
import com.securityhub.user.dto.UserSummary;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.isActive(), user.getCompany().getId(), user.getCompany().getName(),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    public static UserSummary toSummary(User user) {
        if (user == null) {
            return null;
        }
        return new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
