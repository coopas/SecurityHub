package com.securityhub.asset;

import com.securityhub.shared.repository.Specs;
import org.springframework.data.jpa.domain.Specification;

final class AssetSpecifications {

    private AssetSpecifications() {
    }

    static Specification<Asset> filter(Long companyId, String search, Long projectId, AssetType type,
                                       Environment environment, Criticality criticality) {
        return Specification.<Asset>where(Specs.company(companyId))
                .and(Specs.eqNested("project", "id", projectId))
                .and(Specs.eq("type", type))
                .and(Specs.eq("environment", environment))
                .and(Specs.eq("criticality", criticality))
                .and(Specs.containsAny(search, "name", "description", "identifier"));
    }
}
