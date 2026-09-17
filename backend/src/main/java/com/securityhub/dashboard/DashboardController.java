package com.securityhub.dashboard;

import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Four endpoints, exactly the four of docs/api-examples.md. There is deliberately no fifth one for the
 * "itens recentes" panel of §10: that panel is
 * {@code GET /api/v1/vulnerabilities?page=0&size=5&sort=createdAt,desc}, an endpoint that
 * already exists, is paged, tenant-scoped, role-checked and tested. A second listing would
 * be a parallel implementation of the same read.
 *
 * No logic and no {@code @PreAuthorize} here: authorization for the dashboard is
 * "authenticated", enforced by {@code SecurityConfig} (see {@link DashboardService}).
 */
@Tag(name = "Dashboard")
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @Operation(summary = "Cards do dashboard da empresa do usuário autenticado")
    public DashboardSummaryResponse summary(@AuthenticationPrincipal AuthenticatedUser current) {
        return dashboardService.summary(current);
    }

    @GetMapping("/severity-distribution")
    @Operation(summary = "Contagem por severidade, com todas as severidades presentes")
    public List<SeverityDistributionResponse> severityDistribution(
            @AuthenticationPrincipal AuthenticatedUser current) {
        return dashboardService.severityDistribution(current);
    }

    @GetMapping("/status-distribution")
    @Operation(summary = "Contagem por status, com todos os status presentes")
    public List<StatusDistributionResponse> statusDistribution(
            @AuthenticationPrincipal AuthenticatedUser current) {
        return dashboardService.statusDistribution(current);
    }

    @GetMapping("/trend")
    @Operation(summary = "Série diária de abertas e resolvidas; days é limitado a [1, 90]")
    public TrendResponse trend(@AuthenticationPrincipal AuthenticatedUser current,
                               @RequestParam(defaultValue = "30") int days) {
        return dashboardService.trend(current, days);
    }
}
