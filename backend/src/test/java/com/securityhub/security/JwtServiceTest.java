package com.securityhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securityhub.company.Company;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-value-with-more-than-32-bytes-of-material";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpirationMinutes(60);
        jwtService = new JwtService(properties);
        jwtService.init();

        Company company = new Company("Acme", "acme");
        ReflectionTestUtils.setField(company, "id", 7L);
        user = new User(company, "Ana", "ana@acme.com", "hash", Role.ANALYST);
        ReflectionTestUtils.setField(user, "id", 42L);
    }

    @Test
    void generatesTokenCarryingSubjectCompanyAndRole() {
        Optional<JwtPrincipal> principal = jwtService.parse(jwtService.generateAccessToken(user));

        assertThat(principal).isPresent();
        assertThat(principal.get().getUserId()).isEqualTo(42L);
        assertThat(principal.get().getCompanyId()).isEqualTo(7L);
        assertThat(principal.get().getRole()).isEqualTo(Role.ANALYST);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String forged = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject("42")
                .claim("companyId", 7L)
                .claim("role", "ADMIN")
                .setIssuedAt(new Date())
                .setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "a-completely-different-secret-of-sufficient-length".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();

        assertThat(jwtService.parse(forged)).isEmpty();
    }

    @Test
    void rejectsExpiredToken() {
        String expired = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject("42")
                .claim("companyId", 7L)
                .claim("role", "ADMIN")
                .setIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .setExpiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();

        assertThat(jwtService.parse(expired)).isEmpty();
    }

    @Test
    void rejectsTamperedPayload() {
        String token = jwtService.generateAccessToken(user);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AB." + parts[2];

        assertThat(jwtService.parse(tampered)).isEmpty();
    }

    @Test
    void rejectsUnsignedToken() {
        String unsigned = Jwts.builder()
                .setIssuer("securityhub")
                .setSubject("42")
                .claim("companyId", 7L)
                .claim("role", "ADMIN")
                .compact();

        assertThat(jwtService.parse(unsigned)).isEmpty();
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        String otherIssuer = Jwts.builder()
                .setIssuer("someone-else")
                .setSubject("42")
                .claim("companyId", 7L)
                .claim("role", "ADMIN")
                .setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();

        assertThat(jwtService.parse(otherIssuer)).isEmpty();
    }

    @Test
    void refusesToStartWithoutSecret() {
        JwtProperties empty = new JwtProperties();
        empty.setSecret("  ");

        assertThatThrownBy(() -> new JwtService(empty).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITYHUB_JWT_SECRET");
    }

    @Test
    void refusesShortSecret() {
        JwtProperties weak = new JwtProperties();
        weak.setSecret("too-short");

        assertThatThrownBy(() -> new JwtService(weak).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ao menos 32 bytes");
    }
}
