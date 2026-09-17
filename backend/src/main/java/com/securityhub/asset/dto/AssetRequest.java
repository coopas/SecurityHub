package com.securityhub.asset.dto;

import com.securityhub.asset.AssetType;
import com.securityhub.asset.Criticality;
import com.securityhub.asset.Environment;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * There is deliberately no {@code companyId} here: the tenant comes from the authenticated
 * principal (docs/permissions.md). {@code projectId} is accepted but always validated against the
 * caller's company before it is used.
 */
@Getter
@Setter
public class AssetRequest {

    @NotNull(message = "é obrigatório")
    private Long projectId;

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 140, message = "deve ter entre 2 e 140 caracteres")
    private String name;

    @Size(max = 2000, message = "deve ter no máximo 2000 caracteres")
    private String description;

    @NotNull(message = "é obrigatório")
    private AssetType type;

    /** Optional: blank means absent, and several assets may have no identifier. */
    @Size(max = 255, message = "deve ter no máximo 255 caracteres")
    private String identifier;

    @NotNull(message = "é obrigatório")
    private Environment environment;

    @NotNull(message = "é obrigatório")
    private Criticality criticality;
}
