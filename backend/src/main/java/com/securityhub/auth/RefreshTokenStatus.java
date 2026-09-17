package com.securityhub.auth;

public enum RefreshTokenStatus {

    /** Emitido e ainda não apresentado. No máximo um por família em operação normal. */
    ACTIVE,

    /** Já trocado por um sucessor. Reapresentá-lo fora da janela de graça é reuso. */
    ROTATED,

    /** Morto por um evento explícito; sempre acompanhado de um {@link RevocationReason}. */
    REVOKED
}
