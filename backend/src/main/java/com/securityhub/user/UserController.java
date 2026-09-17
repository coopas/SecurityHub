package com.securityhub.user;

import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Usuários")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "Lista os usuários da empresa autenticada")
    public List<UserResponse> list(@AuthenticationPrincipal AuthenticatedUser current,
                                   @RequestParam(required = false) Role role,
                                   @RequestParam(required = false) Boolean active,
                                   @RequestParam(required = false) String search) {
        return userService.search(current, role, active, search);
    }
}
