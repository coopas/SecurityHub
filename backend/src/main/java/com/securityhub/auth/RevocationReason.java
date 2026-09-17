package com.securityhub.auth;

/**
 * Por que uma linha deixou de valer. Existe para a investigação de um incidente: sem o motivo,
 * uma família morta por roubo seria indistinguível de um logout na leitura da tabela.
 */
public enum RevocationReason {

    LOGOUT,
    REUSE_DETECTED,
    PASSWORD_RESET,
    USER_DEACTIVATED,
    ROLE_CHANGED
}
