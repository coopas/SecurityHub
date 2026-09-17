package com.securityhub.comment.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The vulnerability comes from the path and the author and company from the principal, so
 * the body carries nothing but the text.
 */
@Getter
@Setter
@NoArgsConstructor
public class CommentRequest {

    @NotBlank(message = "é obrigatório")
    @Size(max = 2000, message = "deve ter no máximo 2000 caracteres")
    private String content;
}
