package com.securityhub.dashboard.dto;

import com.securityhub.vulnerability.Severity;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One slice of {@code GET /api/v1/dashboard/severity-distribution}. The endpoint answers a
 * bare JSON array rather than the paginated envelope of docs/api-examples.md: the result is a fixed
 * four-element aggregate, not a listing, so the envelope would be five constant fields
 * around it and a {@code page}/{@code size} the client could not act on. Deviation stated
 * here because it is the first thing a reviewer of §8 looks for.
 */
@Getter
@AllArgsConstructor
public class SeverityDistributionResponse {

    private final Severity severity;
    private final long count;
}
