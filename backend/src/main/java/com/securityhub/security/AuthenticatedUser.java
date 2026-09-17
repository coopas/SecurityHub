package com.securityhub.security;

import com.securityhub.user.Role;
import com.securityhub.user.User;
import java.util.Collection;
import java.util.Collections;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Principal of every authenticated request. {@code companyId} is resolved from the
 * validated token and from the database row, never from client input, which is what keeps
 * tenant isolation from depending on request parameters.
 */
@Getter
public class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final Long companyId;
    private final String email;
    private final String name;
    private final Role role;
    private final boolean active;

    public AuthenticatedUser(Long id, Long companyId, String email, String name, Role role, boolean active) {
        this.id = id;
        this.companyId = companyId;
        this.email = email;
        this.name = name;
        this.role = role;
        this.active = active;
    }

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getCompany().getId(), user.getEmail(),
                user.getName(), user.getRole(), user.isActive());
    }

    public boolean hasRole(Role other) {
        return role == other;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
