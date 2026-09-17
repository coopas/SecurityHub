package com.securityhub.scan.dto;

import com.securityhub.scan.ScanFindingStatus;
import com.securityhub.vulnerability.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One staged finding as the preview screen shows it.
 *
 * <p>{@code assetId} and {@code assetName} are absent while the finding is UNMATCHED, and
 * {@code vulnerabilityId} is absent until it is IMPORTED — absent and not null, because
 * {@code default-property-inclusion: non_null} drops them from the payload.
 */
@Getter
@AllArgsConstructor
public class ScanFindingResponse {

    private final Long id;
    /** NSE script id, ZAP pluginid or nuclei template-id, as the report gave it. */
    private final String ruleId;
    private final String title;
    private final String description;
    private final Severity severity;
    private final BigDecimal cvssScore;
    private final String cve;
    /** The host, IP or URL the scanner reported; what the asset identifier is matched against. */
    private final String target;
    private final Instant discoveredAt;
    private final ScanFindingStatus status;
    private final Long assetId;
    private final String assetName;
    private final Long vulnerabilityId;
}
