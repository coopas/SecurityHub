package com.securityhub.auth;

import com.securityhub.auth.dto.AuthResponse;
import com.securityhub.auth.dto.LoginRequest;
import com.securityhub.auth.dto.RegisterRequest;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.UserMapper;
import com.securityhub.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autenticação")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @SecurityRequirements
    @PostMapping("/register")
    @Operation(summary = "Cria uma empresa e o seu primeiro usuário administrador")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @SecurityRequirements
    @PostMapping("/login")
    @Operation(summary = "Autentica e devolve o access token")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Dados do usuário autenticado")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser current) {
        return UserMapper.toResponse(authService.requireUser(current.getId()));
    }
}
