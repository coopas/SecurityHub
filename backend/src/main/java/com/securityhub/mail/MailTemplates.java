package com.securityhub.mail;

/**
 * Plain text, pt-BR, no Spring and no I/O: the body of every message is a pure function of its
 * arguments, so the wording and — what actually matters — the shape of the link are covered by
 * a unit test instead of by watching an inbox.
 */
public final class MailTemplates {

    public static final String PASSWORD_RESET_SUBJECT = "SecurityHub — redefinição de senha";
    public static final String INVITATION_SUBJECT = "SecurityHub — convite para participar";

    private MailTemplates() {
    }

    /** {@code link} already arrives with the token; this class never concatenates it itself. */
    public static String passwordReset(String name, String link, long ttlMinutes) {
        return "Olá, " + name + ".\n\n"
                + "Recebemos um pedido para redefinir a senha da sua conta no SecurityHub.\n"
                + "Acesse o endereço abaixo para escolher uma nova senha:\n\n"
                + link + "\n\n"
                + "O link vale por " + ttlMinutes + " minutos e só pode ser usado uma vez.\n"
                + "Se não foi você quem pediu, ignore esta mensagem: sua senha atual continua valendo.\n\n"
                + "SecurityHub\n";
    }

    public static String invitation(String name, String companyName, String inviterName,
                                    String link, long ttlDays) {
        return "Olá, " + name + ".\n\n"
                + inviterName + " convidou você para participar da empresa " + companyName
                + " no SecurityHub.\n"
                + "Acesse o endereço abaixo para definir sua senha e ativar o acesso:\n\n"
                + link + "\n\n"
                + "O convite vale por " + ttlDays + " dias e só pode ser aceito uma vez.\n\n"
                + "SecurityHub\n";
    }
}
