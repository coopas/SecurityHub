package com.securityhub.scan.dto;

import javax.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PATCH /{id}/findings/{findingId}}: the asset an operator chose for a finding
 * whose target did not resolve on its own.
 *
 * <p>{@code assetId} is required. There is no "unmap" in this request because there is nothing
 * to undo: a mapping only ever moves a finding from UNMATCHED to MATCHED, and an import that
 * was mapped wrongly is discarded and uploaded again, which costs nothing while it is still a
 * proposal.
 */
@Getter
@Setter
@NoArgsConstructor
public class ScanFindingMappingRequest {

    @NotNull(message = "O ativo é obrigatório")
    private Long assetId;
}
