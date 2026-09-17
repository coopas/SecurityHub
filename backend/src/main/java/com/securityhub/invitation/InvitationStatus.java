package com.securityhub.invitation;

public enum InvitationStatus {

    /** Vivo: ainda pode ser aceito. É o estado coberto pelo índice único parcial de V7. */
    PENDING,

    ACCEPTED,

    /** Cancelado por um administrador, ou substituído por um convite novo ao mesmo endereço. */
    REVOKED
}
