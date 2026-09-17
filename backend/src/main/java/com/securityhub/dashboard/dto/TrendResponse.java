package com.securityhub.dashboard.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * {@code GET /api/v1/dashboard/trend?days=30}.
 *
 * {@code days}, {@code from} and {@code to} echo the window the server actually used. The
 * request value is clamped silently to [1, 90] — a chart control must not be able to raise
 * an error dialog, mirroring the page-size clamp of {@code PageableSupport} — and echoing
 * the effective value is what keeps that clamp honest instead of hidden.
 */
@Getter
@AllArgsConstructor
public class TrendResponse {

    private final int days;
    /** First day of the window, inclusive: {@code to - (days - 1)}. */
    private final LocalDate from;
    /** Today in UTC, inclusive. */
    private final LocalDate to;
    /** Exactly {@code days} points, oldest first. */
    private final List<TrendPointResponse> points;
}
