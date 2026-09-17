package com.securityhub.user;

import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.dto.UserResponse;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    /**
     * ANALYST is included because assigning a vulnerability (docs/permissions.md) requires choosing a
     * user; the endpoint is read-only and never leaves the caller's company.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public List<UserResponse> search(AuthenticatedUser current, Role role, Boolean active, String search) {
        String term = (search == null || search.trim().isEmpty())
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.search(current.getCompanyId(), role, active, term).stream()
                .map(UserMapper::toResponse)
                .collect(Collectors.toList());
    }
}
