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
 * Delivery that never brings the request down (ADR 0007).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Mailer {

    private final JavaMailSender mailSender;
    private final MailerProperties properties;

    /**
     * Queues the message for after the commit, for two independent reasons:
     *
     * <ul>
     *   <li><b>After the commit</b> because the link points at a row that is not committed
     *       yet. Sending it earlier would open the window in which the recipient clicks a
     *       token the transaction can still undo — and a rollback would leave an e-mail out
     *       in the world about an invitation that never existed.</li>
     *   <li><b>Off the request thread</b> because the round trip to SMTP is the only timing
     *       difference between a known address and an unknown one in
     *       {@code POST /auth/password-reset/request}. Keeping it on the synchronous path
     *       would turn the 202 response — deliberately identical in both cases — into an
     *       account enumeration oracle measurable with a stopwatch.</li>
     * </ul>
     *
     * With no active transaction (a caller outside a transactional service) the send happens
     * directly, which is still asynchronous and still does not propagate failure.
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
     * A delivery failure becomes a log line, never an exception: the caller has already
     * committed and has nothing left to undo. WARN and not ERROR because a missing SMTP is the
     * normal state of a development environment without MailHog up.
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
     * The rejection from the bounded executor arrives here, on the thread of whoever committed,
     * as a TaskRejectedException; that is why the asynchronous call is guarded as well.
     */
    private void dispatch(String to, String subject, String body) {
        try {
            send(to, subject, body);
        } catch (RuntimeException ex) {
            log.warn("E-mail '{}' descartado: {}", subject, ex.getClass().getSimpleName());
        }
    }
}
