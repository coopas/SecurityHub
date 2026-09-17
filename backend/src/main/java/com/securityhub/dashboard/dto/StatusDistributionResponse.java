package com.securityhub.dashboard.dto;

import com.securityhub.vulnerability.VulnerabilityStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Twin of {@link SeverityDistributionResponse}, including the bare-array deviation. */
@Getter
@AllArgsConstructor
public class StatusDistributionResponse {

    private final VulnerabilityStatus status;
    private final long count;
}
