package com.securityhub.user;

import com.securityhub.audit.AuditAction;
import com.securityhub.audit.AuditEntry;
import com.securityhub.audit.AuditService;
import com.securityhub.auth.RefreshTokenService;
import com.securityhub.auth.RevocationReason;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.NotFoundException;
import com.securityhub.user.dto.ActiveChangeRequest;
import com.securityhub.user.dto.RoleChangeRequest;
import com.securityhub.user.dto.UserResponse;
import com.securityhub.user.dto.UserUpdateRequest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    static final String ENTITY_TYPE = "User";
    static final String LAST_ADMIN = "A empresa precisa de ao menos um administrador ativo";
    static final String SELF_DEACTIVATION = "Você não pode desativar a própria conta";

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;

    /**
     * ANALYST is included because assigning a vulnerability (docs/permissions.md) requires choosing a
     * user; the endpoint is read-only and never leaves the caller's company.
     *
     * Returns a plain array and not a page. That is not an oversight: two frontend services
     * consume the response with a direct cast to {@code User[]}, and wrapping it in a
     * {@code PageResponse} would break both at runtime, with no compilation error.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public List<UserResponse> search(AuthenticatedUser current, Role role, Boolean active, String search) {
        return userRepository
                .findAll(UserSpecifications.filter(current.getCompanyId(), role, active, search),
                        Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .map(UserMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse get(AuthenticatedUser current, Long id) {
        return UserMapper.toResponse(require(current, id));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(AuthenticatedUser current, Long id, UserUpdateRequest request) {
        User user = require(current, id);
        Map<String, Object> before = snapshot(user);

        user.setName(request.getName().trim());
        userRepository.save(user);

        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        return UserMapper.toResponse(user);
    }

    /**
     * The order of the guards, and it matters:
     *
     * <ol>
     *   <li>{@code require} — a user from another company is a 404 before any ownership rule,
     *       otherwise the 403 would confirm that the id exists somewhere;</li>
     *   <li>last active administrator — demoting the only active ADMIN would leave the company
     *       with nobody able to administer it, including to promote a replacement;</li>
     *   <li>self-demotion is allowed when another active ADMIN exists, and the guard above
     *       already covers the case where none does.</li>
     * </ol>
     *
     * A pending ADMIN invitation does not count: the count is of active {@code users} rows, and
     * an invitation is not yet anybody.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeRole(AuthenticatedUser current, Long id, RoleChangeRequest request) {
        User user = require(current, id);
        if (user.getRole() == request.getRole()) {
            return UserMapper.toResponse(user);
        }
        ensureNotLastActiveAdmin(current, user);

        Map<String, Object> before = snapshot(user);
        user.setRole(request.getRole());
        userRepository.save(user);
        // The role travels inside the access token; keeping it valid would leave the old
        // permission standing until it expires. The filter already refuses an access token
        // whose role diverged from the row, and revoking the refresh closes the other side:
        // renewing the session requires logging in again.
        refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.ROLE_CHANGED);

        UserResponse response = UserMapper.toResponse(user);
        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        log.info("Papel do usuário {} alterado para {} na empresa {}", id, request.getRole(),
                current.getCompanyId());
        return response;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse changeActive(AuthenticatedUser current, Long id, ActiveChangeRequest request) {
        User user = require(current, id);
        boolean active = Boolean.TRUE.equals(request.getActive());
        if (user.isActive() == active) {
            return UserMapper.toResponse(user);
        }
        if (!active) {
            ensureNotLastActiveAdmin(current, user);
            // Always forbidden, even when other administrators exist. Deactivating yourself is
            // a shot in the foot the API itself cannot undo — the account loses the access it
            // would need to reactivate itself — and it is never what anybody meant to do.
            if (user.getId().equals(current.getId())) {
                throw new ConflictException(SELF_DEACTIVATION);
            }
        }

        Map<String, Object> before = snapshot(user);
        user.setActive(active);
        userRepository.save(user);
        if (!active) {
            refreshTokenService.revokeAllForUser(user.getId(), RevocationReason.USER_DEACTIVATED);
        }
        // Reactivating revokes nothing: there is no session to end, and the rows that existed
        // already died on the deactivation.

        UserResponse response = UserMapper.toResponse(user);
        auditService.record(AuditEntry.changed(current, AuditAction.USER_UPDATED, ENTITY_TYPE, id,
                before, snapshot(user)));
        log.info("Usuário {} {} na empresa {}", id, active ? "reativado" : "desativado",
                current.getCompanyId());
        return response;
    }

    /**
     * Only fires for an active ADMIN that is about to stop counting — demoted or deactivated.
     * Promoting, demoting somebody who is no longer an ADMIN, or deactivating somebody who is
     * already inactive do not move the count.
     */
    private void ensureNotLastActiveAdmin(AuthenticatedUser current, User user) {
        if (user.getRole() != Role.ADMIN || !user.isActive()) {
            return;
        }
        if (userRepository.countByCompanyIdAndRoleAndActiveTrue(current.getCompanyId(), Role.ADMIN) <= 1) {
            throw new ConflictException(LAST_ADMIN);
        }
    }

    /** A user of another company is a 404, never a 403: the API does not confirm the id exists. */
    private User require(AuthenticatedUser current, Long id) {
        return userRepository.findByIdAndCompanyId(id, current.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Usuário", id));
    }

    /** Without the password hash: the sanitizer would mask it, and it has no business here. */
    private Map<String, Object> snapshot(User user) {
        Map<String, Object> values = AuditEntry.values();
        values.put("name", user.getName());
        values.put("email", user.getEmail());
        values.put("role", user.getRole().name());
        values.put("active", user.isActive());
        return values;
    }
}
