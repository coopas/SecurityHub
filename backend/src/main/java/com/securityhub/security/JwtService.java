package com.securityhub.security;

import com.securityhub.user.Role;
import com.securityhub.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.annotation.PostConstruct;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    static final String CLAIM_COMPANY_ID = "companyId";
    static final String CLAIM_ROLE = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private SecretKey key;

    @PostConstruct
    void init() {
        String secret = properties.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("SECURITYHUB_JWT_SECRET não configurado. "
                    + "Gere um valor com: openssl rand -base64 48");
        }
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("SECURITYHUB_JWT_SECRET deve ter ao menos "
                    + MIN_SECRET_BYTES + " bytes; recebidos " + material.length);
        }
        this.key = Keys.hmacShaKeyFor(material);
    }

    public String generateAccessToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenTtl());
        return Jwts.builder()
                .setIssuer(properties.getIssuer())
                .setSubject(String.valueOf(user.getId()))
                .claim(CLAIM_COMPANY_ID, user.getCompany().getId())
                .claim(CLAIM_ROLE, user.getRole().name())
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public Duration accessTokenTtl() {
        return Duration.ofMinutes(properties.getExpirationMinutes());
    }

    /**
     * Lifetime of the refresh token. It lives here, and not in RefreshTokenService, because the
     * property that defines it — securityhub.jwt.refresh-expiration-days — already belonged to
     * this class; the token itself is opaque and goes through no other method here (ADR 0006).
     */
    public Duration refreshTokenTtl() {
        return Duration.ofDays(properties.getRefreshExpirationDays());
    }

    public Optional<JwtPrincipal> parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseClaimsJws(token);
            Claims claims = jws.getBody();
            Long userId = Long.valueOf(claims.getSubject());
            Long companyId = claims.get(CLAIM_COMPANY_ID, Number.class).longValue();
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            return Optional.of(new JwtPrincipal(userId, companyId, role));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            log.debug("Token rejeitado: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
