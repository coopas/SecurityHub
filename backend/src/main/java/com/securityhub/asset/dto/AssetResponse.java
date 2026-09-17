package com.securityhub.asset.dto;

import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AssetResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final AssetType type;
    private final String identifier;
    private final Environment environment;
    private final Criticality criticality;
    private final Long projectId;
    private final String projectName;
    /** Supplied by the service from a grouped count query, never from a mapped collection. */
    private final long vulnerabilityCount;
    private final Instant createdAt;
    private final Instant updatedAt;
}
