package com.securityhub.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure: no Spring context and no SMTP. The content of the message is a function of its
 * arguments, so this is where it is checked — never by waiting on an inbox.
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

        // The e-mail carries a single-use link and nothing else. A provisional password in the
        // body would stay valid in the inbox forever, and a hash there would be worse still.
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
