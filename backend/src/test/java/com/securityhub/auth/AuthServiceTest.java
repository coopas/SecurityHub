package com.securityhub.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securityhub.audit.AuditService;
import com.securityhub.auth.dto.LoginRequest;
import com.securityhub.auth.dto.RefreshTokenRequest;
import com.securityhub.auth.dto.RegisterRequest;
import com.securityhub.company.Company;
import com.securityhub.company.CompanyRepository;
import com.securityhub.security.JwtService;
import com.securityhub.security.TestJwtServiceFactory;
import com.securityhub.shared.error.ConflictException;
import com.securityhub.shared.error.UnauthorizedException;
import com.securityhub.user.Role;
import com.securityhub.user.User;
import com.securityhub.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private RefreshTokenService refreshTokenService;

    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);

        JwtService jwtService = TestJwtServiceFactory.create();

        authService = new AuthService(companyRepository, userRepository, passwordEncoder, jwtService,
                auditService, refreshTokenService, new SimpleMeterRegistry());
        authService.init();
    }

    @Test
    void registerCreatesCompanyAndAdminWithHashedPassword() {
        when(userRepository.existsByEmail("ADMIN@Acme.com".toLowerCase())).thenReturn(false);
        when(companyRepository.existsBySlug(anyString())).thenReturn(false);
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            ReflectionTestUtils.setField(company, "id", 1L);
            return company;
        });
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return user;
        });

        RegisterRequest request = new RegisterRequest();
        request.setCompanyName("Acme Segurança");
        request.setName("Admin");
        request.setEmail("ADMIN@Acme.com");
        request.setPassword("senha-super-segura");

        authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getEmail()).isEqualTo("admin@acme.com");
        assertThat(saved.getPasswordHash()).isNotEqualTo("senha-super-segura");
        assertThat(passwordEncoder.matches("senha-super-segura", saved.getPasswordHash())).isTrue();
        assertThat(saved.getCompany().getSlug()).isEqualTo("acme-seguranca");
    }

    @Test
    void registerRejectsDuplicatedEmail() {
        when(userRepository.existsByEmail("admin@acme.com")).thenReturn(true);

        RegisterRequest request = new RegisterRequest();
        request.setCompanyName("Acme");
        request.setName("Admin");
        request.setEmail("admin@acme.com");
        request.setPassword("senha-super-segura");

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(ConflictException.class);
        verify(companyRepository, never()).save(any());
    }

    @Test
    void loginReturnsTokenForValidCredentials() {
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(activeUser("senha-correta")));

        LoginRequest request = new LoginRequest();
        request.setEmail("Ana@Acme.com");
        request.setPassword("senha-correta");

        assertThat(authService.login(request).getAccessToken()).isNotBlank();
    }

    @Test
    void loginWithUnknownEmailAndWrongPasswordFailIdentically() {
        when(userRepository.findByEmail("desconhecido@acme.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(activeUser("senha-correta")));

        LoginRequest unknown = new LoginRequest();
        unknown.setEmail("desconhecido@acme.com");
        unknown.setPassword("qualquer-senha");

        LoginRequest wrongPassword = new LoginRequest();
        wrongPassword.setEmail("ana@acme.com");
        wrongPassword.setPassword("senha-errada");

        String unknownMessage = catchMessage(unknown);
        String wrongPasswordMessage = catchMessage(wrongPassword);

        assertThat(unknownMessage).isEqualTo(wrongPasswordMessage).isEqualTo("Credenciais inválidas");
    }

    @Test
    void loginRejectsInactiveUser() {
        User user = activeUser("senha-correta");
        user.setActive(false);
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest();
        request.setEmail("ana@acme.com");
        request.setPassword("senha-correta");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Credenciais inválidas");
    }

    @Test
    void loginRecordsLastLogin() {
        User user = activeUser("senha-correta");
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(user));

        LoginRequest request = new LoginRequest();
        request.setEmail("ana@acme.com");
        request.setPassword("senha-correta");
        authService.login(request);

        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    void loginIssuesARefreshTokenAndCollectsTheExpiredOnesOfThatUser() {
        User user = activeUser("senha-correta");
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(user));
        when(refreshTokenService.issue(user)).thenReturn("token-opaco");

        LoginRequest request = new LoginRequest();
        request.setEmail("ana@acme.com");
        request.setPassword("senha-correta");

        assertThat(authService.login(request).getRefreshToken()).isEqualTo("token-opaco");
        verify(refreshTokenService).purgeExpiredFor(2L);
    }

    @Test
    void aFailedLoginOpensNoSession() {
        when(userRepository.findByEmail("ana@acme.com")).thenReturn(Optional.of(activeUser("certa")));

        LoginRequest request = new LoginRequest();
        request.setEmail("ana@acme.com");
        request.setPassword("errada");

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(UnauthorizedException.class);
        verify(refreshTokenService, never()).issue(any());
        verify(refreshTokenService, never()).purgeExpiredFor(any());
    }

    @Test
    void refreshRejectsWhenTheRotatedRowPointsAtAUserThatIsGone() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("qualquer");
        when(refreshTokenService.rotate("qualquer"))
                .thenReturn(new RefreshTokenService.Rotation(2L, "novo"));
        when(userRepository.findWithCompanyById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Sessão inválida");
    }

    @Test
    void refreshHandsBackTheNewlyIssuedToken() {
        User user = activeUser("senha-correta");
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("antigo");
        when(refreshTokenService.rotate("antigo"))
                .thenReturn(new RefreshTokenService.Rotation(2L, "novo"));
        when(userRepository.findWithCompanyById(2L)).thenReturn(Optional.of(user));

        assertThat(authService.refresh(request).getRefreshToken()).isEqualTo("novo");
        assertThat(authService.refresh(request).getAccessToken()).isNotBlank();
    }

    private String catchMessage(LoginRequest request) {
        try {
            authService.login(request);
            throw new AssertionError("login deveria ter falhado");
        } catch (UnauthorizedException ex) {
            return ex.getMessage();
        }
    }

    private User activeUser(String rawPassword) {
        Company company = new Company("Acme", "acme");
        ReflectionTestUtils.setField(company, "id", 1L);
        User user = new User(company, "Ana", "ana@acme.com", passwordEncoder.encode(rawPassword), Role.ANALYST);
        ReflectionTestUtils.setField(user, "id", 2L);
        return user;
    }
}
