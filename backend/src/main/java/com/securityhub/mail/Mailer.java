package com.securityhub.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Entrega que nunca derruba a requisição (ADR 0007).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Mailer {

    private final JavaMailSender mailSender;
    private final MailerProperties properties;

    /**
     * Enfileira a mensagem para depois do commit, por duas razões independentes:
     *
     * <ul>
     *   <li><b>Depois do commit</b> porque o link aponta para uma linha que ainda não está
     *       comitada. Enviar antes abriria a janela em que o destinatário clica em um token
     *       que a transação ainda pode desfazer — e um rollback deixaria no mundo um e-mail
     *       sobre um convite que nunca existiu.</li>
     *   <li><b>Fora da thread da requisição</b> porque a ida ao SMTP é a única diferença de
     *       tempo entre um endereço conhecido e um desconhecido em
     *       {@code POST /auth/password-reset/request}. Mantê-la no caminho síncrono
     *       transformaria a resposta 202 — deliberadamente idêntica nos dois casos — em um
     *       oráculo de enumeração de contas medível com um cronômetro.</li>
     * </ul>
     *
     * Sem transação ativa (um chamador fora de um serviço transacional) o envio acontece
     * direto, que continua sendo assíncrono e continua sem propagar falha.
     */
    public void sendAfterCommit(String to, String subject, String body) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(to, subject, body);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(to, subject, body);
            }
        });
    }

    /**
     * Falha de entrega vira log, nunca exceção: quem chamou já comitou e não tem mais o que
     * desfazer. WARN e não ERROR porque um SMTP ausente é o estado normal de um ambiente de
     * desenvolvimento sem o MailHog de pé.
     */
    @Async(MailAsyncConfig.EXECUTOR)
    public void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getFrom());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.debug("E-mail '{}' enviado", subject);
        } catch (RuntimeException ex) {
            log.warn("Falha ao enviar e-mail '{}': {}: {}", subject, ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /**
     * A rejeição do executor limitado chega aqui, na thread de quem comitou, como
     * TaskRejectedException; por isso a chamada assíncrona também é protegida.
     */
    private void dispatch(String to, String subject, String body) {
        try {
            send(to, subject, body);
        } catch (RuntimeException ex) {
            log.warn("E-mail '{}' descartado: {}", subject, ex.getClass().getSimpleName());
        }
    }
}
