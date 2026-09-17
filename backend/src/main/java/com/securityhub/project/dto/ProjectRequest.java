package com.securityhub.project.dto;

import com.securityhub.project.ProjectStatus;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectRequest {

    @NotBlank(message = "é obrigatório")
    @Size(min = 2, max = 140, message = "deve ter entre 2 e 140 caracteres")
    private String name;

    @Size(max = 2000, message = "deve ter no máximo 2000 caracteres")
    private String description;

    /** Opcional na criação: ausente significa {@code ACTIVE}. */
    private ProjectStatus status;
}
