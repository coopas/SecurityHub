package com.securityhub.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securityhub.asset.AssetRepository;
import com.securityhub.dashboard.dto.DashboardSummaryResponse;
import com.securityhub.dashboard.dto.SeverityDistributionResponse;
import com.securityhub.dashboard.dto.StatusDistributionResponse;
import com.securityhub.dashboard.dto.TrendResponse;
import com.securityhub.project.ProjectRepository;
import com.securityhub.security.AuthenticatedUser;
import com.securityhub.user.Role;
import com.securityhub.vulnerability.Severity;
import com.securityhub.vulnerability.VulnerabilityStatus;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure logic only: the clamp, the window arithmetic and the zero-fill. Everything that needs
 * real SQL — the aggregations themselves, the UTC bucketing and the tenant scope — is proven
 * by {@code DashboardIntegrationTest} against a real PostgreSQL, because a mocked repository
 * cannot fail the way a wrong query does.
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Long COMPANY_ID = 42L;

    private final AuthenticatedUser current =
            new AuthenticatedUser(7L, COMPANY_ID, "viewer@acme.test", "Viewer", Role.VIEWER, true);

    @Mock
    private DashboardRepository dashboardRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AssetRepository assetRepository;

    @InjectMocks
    private DashboardService dashboardService;

    // --- distributions ------------------------------------------------------

    @Test
    void severityDistributionReturnsEveryConstantInDeclarationOrderEvenWhenUnused() {
        // The database only ever returns the severities that occur, and in no fixed order.
        when(dashboardRepository.countBySeverity(COMPANY_ID)).thenReturn(Arrays.asList(
                new Object[] {Severity.CRITICAL, 5L},
                new Object[] {Severity.LOW, 2L}));

        List<SeverityDistributionResponse> distribution = dashboardService.severityDistribution(current);

        assertThat(distribution).extracting(SeverityDistributionResponse::getSeverity)
                .containsExactly(Severity.values());
        assertThat(distribution).extracting(SeverityDistributionResponse::getCount)
                .containsExactly(2L, 0L, 0L, 5L);
    }

    @Test
    void statusDistributionReturnsEveryConstantInDeclarationOrderEvenWhenUnused() {
        when(dashboardRepository.countByStatus(COMPANY_ID)).thenReturn(Collections.singletonList(
                new Object[] {VulnerabilityStatus.IN_PROGRESS, 3L}));

        List<StatusDistributionResponse> distribution = dashboardService.statusDistribution(current);

        assertThat(distribution).extracting(StatusDistributionResponse::getStatus)
                .containsExactly(VulnerabilityStatus.values());
        assertThat(distribution).extracting(StatusDistributionResponse::getCount)
                .containsExactly(0L, 3L, 0L, 0L);
    }

    @Test
    void distributionsOfACompanyWithoutRowsAreStillComplete() {
        when(dashboardRepository.countBySeverity(COMPANY_ID)).thenReturn(Collections.emptyList());
        when(dashboardRepository.countByStatus(COMPANY_ID)).thenReturn(Collections.emptyList());

        assertThat(dashboardService.severityDistribution(current)).hasSize(Severity.values().length)
                .allMatch(entry -> entry.getCount() == 0L);
        assertThat(dashboardService.statusDistribution(current)).hasSize(VulnerabilityStatus.values().length)
                .allMatch(entry -> entry.getCount() == 0L);
    }

    // --- trend window -------------------------------------------------------

    @Test
    void trendWindowEndsTodayAndSpansExactlyTheRequestedDays() {
        when(dashboardRepository.trend(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        TrendResponse response = dashboardService.trend(current, 30);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(response.getDays()).isEqualTo(30);
        assertThat(response.getTo()).isEqualTo(today);
        // 30 days inclusive on both ends means 29 days back, not 30.
        assertThat(response.getFrom()).isEqualTo(today.minusDays(29));

        ArgumentCaptor<String> fromDay = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toDay = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> fromInstant = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toInstant = ArgumentCaptor.forClass(Instant.class);
        verify(dashboardRepository).trend(eq(COMPANY_ID), fromDay.capture(), toDay.capture(),
                fromInstant.capture(), toInstant.capture());

        assertThat(fromDay.getValue()).isEqualTo(today.minusDays(29).toString());
        assertThat(toDay.getValue()).isEqualTo(today.toString());
        assertThat(fromInstant.getValue())
                .isEqualTo(today.minusDays(29).atStartOfDay(ZoneOffset.UTC).toInstant());
        // Half-open upper bound: the start of tomorrow, so today is fully included.
        assertThat(toInstant.getValue())
                .isEqualTo(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    @Test
    void trendClampsDaysIntoOneToNinetyWithoutFailing() {
        assertThat(DashboardService.clampDays(0)).isEqualTo(1);
        assertThat(DashboardService.clampDays(-500)).isEqualTo(1);
        assertThat(DashboardService.clampDays(1)).isEqualTo(1);
        assertThat(DashboardService.clampDays(30)).isEqualTo(30);
        assertThat(DashboardService.clampDays(90)).isEqualTo(90);
        assertThat(DashboardService.clampDays(91)).isEqualTo(90);
        assertThat(DashboardService.clampDays(Integer.MAX_VALUE)).isEqualTo(90);
    }

    @Test
    void trendEchoesTheClampedValueAndAsksTheDatabaseForTheClampedWindow() {
        when(dashboardRepository.trend(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        TrendResponse clampedUp = dashboardService.trend(current, 0);
        assertThat(clampedUp.getDays()).isEqualTo(1);
        assertThat(clampedUp.getFrom()).isEqualTo(clampedUp.getTo());

        TrendResponse clampedDown = dashboardService.trend(current, 5000);
        assertThat(clampedDown.getDays()).isEqualTo(90);
        assertThat(clampedDown.getFrom()).isEqualTo(clampedDown.getTo().minusDays(89));
    }

    @Test
    void trendMapsTheRowsTheDatabaseFilledIncludingEmptyDays() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(dashboardRepository.trend(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(Arrays.asList(
                        // generate_series already filled the gap; BigInteger is what the
                        // native query hands back for count(*) on Hibernate 5.
                        new Object[] {today.minusDays(2).toString(), BigInteger.valueOf(4), BigInteger.ZERO},
                        new Object[] {today.minusDays(1).toString(), BigInteger.ZERO, BigInteger.ZERO},
                        new Object[] {today.toString(), BigInteger.ONE, BigInteger.valueOf(3)}));

        TrendResponse response = dashboardService.trend(current, 3);

        assertThat(response.getPoints()).hasSize(3);
        assertThat(response.getPoints().stream().map(point -> point.getDate().toString())
                .collect(Collectors.toList()))
                .containsExactly(today.minusDays(2).toString(), today.minusDays(1).toString(),
                        today.toString());
        assertThat(response.getPoints().get(0).getOpened()).isEqualTo(4L);
        assertThat(response.getPoints().get(1).getOpened()).isZero();
        assertThat(response.getPoints().get(2).getResolved()).isEqualTo(3L);
    }

    // --- summary ------------------------------------------------------------

    /**
     * Regression guard for the BigInteger trap: with {@code nativeQuery = true} Hibernate 5
     * returns {@code count(*)} as {@link BigInteger} and {@code bigserial} ids as
     * {@link BigInteger} too. Casting either to {@code Long} throws {@code ClassCastException}
     * at runtime, which no compiler catches.
     */
    @Test
    void summaryReadsBigIntegerColumnsAndCarriesTheProjectAndAssetCounts() {
        when(dashboardRepository.summaryCounts(eq(COMPANY_ID), any(Instant.class)))
                .thenReturn(Collections.singletonList(new Object[] {
                        BigInteger.valueOf(7), BigInteger.valueOf(4), BigInteger.valueOf(2),
                        BigInteger.valueOf(2), BigInteger.valueOf(2)}));
        when(dashboardRepository.topProjects(eq(COMPANY_ID), any(Instant.class), anyInt()))
                .thenReturn(Collections.singletonList(new Object[] {
                        BigInteger.valueOf(11), "Portal", BigInteger.valueOf(5),
                        BigInteger.valueOf(3), BigInteger.valueOf(2)}));
        when(projectRepository.countByCompanyId(COMPANY_ID)).thenReturn(3L);
        when(assetRepository.countByCompanyId(COMPANY_ID)).thenReturn(3L);

        DashboardSummaryResponse summary = dashboardService.summary(current);

        assertThat(summary.getTotalVulnerabilities()).isEqualTo(7L);
        assertThat(summary.getOpenVulnerabilities()).isEqualTo(4L);
        assertThat(summary.getCriticalOpenVulnerabilities()).isEqualTo(2L);
        assertThat(summary.getOverdueVulnerabilities()).isEqualTo(2L);
        assertThat(summary.getResolvedVulnerabilities()).isEqualTo(2L);
        assertThat(summary.getTotalProjects()).isEqualTo(3L);
        assertThat(summary.getTotalAssets()).isEqualTo(3L);
        assertThat(summary.getTopProjects()).hasSize(1);
        assertThat(summary.getTopProjects().get(0).getProjectId()).isEqualTo(11L);
        assertThat(summary.getTopProjects().get(0).getProjectName()).isEqualTo("Portal");
        assertThat(summary.getTopProjects().get(0).getTotal()).isEqualTo(5L);
    }

    /** The breakdown is a card, so the service must never ask for an unbounded list. */
    @Test
    void summaryAsksForAtMostTenProjectsAndSurvivesAnEmptyAggregate() {
        when(dashboardRepository.summaryCounts(eq(COMPANY_ID), any(Instant.class)))
                .thenReturn(Collections.emptyList());
        when(dashboardRepository.topProjects(eq(COMPANY_ID), any(Instant.class), anyInt()))
                .thenReturn(Collections.emptyList());

        DashboardSummaryResponse summary = dashboardService.summary(current);

        assertThat(summary.getTotalVulnerabilities()).isZero();
        assertThat(summary.getTopProjects()).isEmpty();
        verify(dashboardRepository).topProjects(eq(COMPANY_ID), any(Instant.class),
                eq(DashboardService.TOP_PROJECTS_LIMIT));
    }
}
