package com.securityhub.mail;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @EnableAsync} was on the application class long before any {@code @Async} method
 * existed, with no executor configured. That combination silently promises a
 * {@code SimpleAsyncTaskExecutor}, which starts one unbounded thread per call: an SMTP relay
 * that stops answering would then be enough to exhaust the process, and the request threads
 * would still look healthy while it happened.
 *
 * The pool is deliberately tiny. Mail here is two rare events — a password reset and an
 * invitation — so the queue is what absorbs a burst, and its bound is what turns a dead relay
 * into dropped messages instead of unbounded memory.
 */
@Configuration
public class MailAsyncConfig {

    /** Referenced by {@code @Async} so the executor is chosen explicitly, never by type. */
    public static final String EXECUTOR = "mailExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("mail-");
        // Abort and not CallerRuns: CallerRuns would push the SMTP round trip back onto the
        // request thread, which is precisely the timing asymmetry ADR 0007 exists to remove.
        // The rejection is caught by Mailer and logged; a lost e-mail beats a stalled request.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
