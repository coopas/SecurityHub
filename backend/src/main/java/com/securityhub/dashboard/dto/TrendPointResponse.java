package com.securityhub.dashboard.dto;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One calendar day of the trend, in UTC. Empty days are present with zeros, so the chart
 * never has to guess where a gap was.
 */
@Getter
@AllArgsConstructor
public class TrendPointResponse {

    private final LocalDate date;
    /** Vulnerabilities whose {@code discoveredAt} falls on this day. */
    private final long opened;
    /** Vulnerabilities whose {@code resolvedAt} falls on this day. */
    private final long resolved;
}
