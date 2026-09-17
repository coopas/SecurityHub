package com.securityhub.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Puro: nenhum contexto Spring e nenhum SMTP. O conteúdo da mensagem é uma função dos
 * argumentos, então é aqui que ele é verificado — nunca esperando por uma caixa de entrada.
 */
class MailTemplatesTest {

    private static final String RESET_LINK = "http://localhost:4200/reset-password?token=abc-123";
    private static final String INVITE_LINK = "http://localhost:4200/accept-invitation?token=xyz-789";

    @Test
    void passwordResetCarriesTheNameTheLinkAndTheWindow() {
        String body = MailTemplates.passwordReset("Ana", RESET_LINK, 30);

        assertThat(body).contains("Ana").contains(RESET_LINK).contains("30 minutos");
    }

    @Test
    void passwordResetTellsTheReaderToIgnoreItWhenItWasNotThem() {
        String body = MailTemplates.passwordReset("Ana", RESET_LINK, 30);

        assertThat(body).contains("ignore esta mensagem");
    }

    @Test
    void passwordResetShipsALinkAndNeverACredential() {
        String body = MailTemplates.passwordReset("Ana", RESET_LINK, 30);

        // O e-mail carrega um link de uso único e nada mais. Uma senha provisória no corpo
        // ficaria válida na caixa de entrada para sempre, e um hash ali seria pior ainda.
        assertThat(body).contains(RESET_LINK)
                .doesNotContain("$2")
                .doesNotContain("senha provisória")
                .doesNotContain("senha temporária");
    }

    @Test
    void invitationCarriesTheCompanyTheInviterAndTheLink() {
        String body = MailTemplates.invitation("Bruno", "Acme Segurança", "Ana", INVITE_LINK, 7);

        assertThat(body).contains("Bruno").contains("Acme Segurança").contains("Ana")
                .contains(INVITE_LINK).contains("7 dias");
    }

    @Test
    void bodiesArePlainTextWithoutMarkup() {
        assertThat(MailTemplates.passwordReset("Ana", RESET_LINK, 30)).doesNotContain("<");
        assertThat(MailTemplates.invitation("Bruno", "Acme", "Ana", INVITE_LINK, 7))
                .doesNotContain("<");
    }

    @Test
    void subjectsIdentifyTheProduct() {
        assertThat(MailTemplates.PASSWORD_RESET_SUBJECT).startsWith("SecurityHub");
        assertThat(MailTemplates.INVITATION_SUBJECT).startsWith("SecurityHub");
    }
}
