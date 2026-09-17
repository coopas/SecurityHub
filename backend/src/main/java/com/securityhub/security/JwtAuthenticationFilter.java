package com.securityhub.security;

import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import java.io.IOException;
import java.util.Optional;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Re-reads the user on every protected request so that a deactivated account, a changed
 * role or a company move takes effect immediately instead of surviving until the token
 * expires.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            bearerToken(request)
                    .flatMap(jwtService::parse)
                    .flatMap(this::loadActiveUser)
                    .ifPresent(user -> authenticate(user, request));
        }
        filterChain.doFilter(request, response);
    }

    private Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private Optional<User> loadActiveUser(JwtPrincipal principal) {
        return userRepository.findById(principal.getUserId())
                .filter(User::isActive)
                .filter(user -> user.getCompany().getId().equals(principal.getCompanyId()))
                .filter(user -> user.getRole() == principal.getRole());
    }

    private void authenticate(User user, HttpServletRequest request) {
        AuthenticatedUser principal = AuthenticatedUser.from(user);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
