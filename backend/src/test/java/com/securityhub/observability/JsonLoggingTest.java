package com.securityhub.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.stacktrace.ShortenedThrowableConverter;
import org.junit.jupiter.api.Test;

/**
 * The JSON appender of {@code logback-spring.xml} is only used by the {@code prod} profile, and
 * that is precisely why this test exists.
 *
 * <p>The defect it guards against has already happened. With the encoder on the 7.3+ line and
 * the logback 1.2.12 that Boot 2.7 manages, the application <em>compiles</em>, <em>starts</em>
 * and configures the appender without a single error message — and dies with {@code
 * NoSuchMethodError: ILoggingEvent.getInstant()} while formatting the first event. That is: on
 * the first production log line, and at no moment before it. The {@code demo} and the {@code
 * test} profiles use the console appender and never touch that path, so the whole suite stayed
 * green over an application that could not record a single line in production.
 *
 * <p>This test formats a real event, which is the only moment the incompatibility shows up. It
 * is fast and does not depend on Spring: if the two version lines diverge again, {@code mvn
 * test} breaks here instead of breaking at the customer.
 */
class JsonLoggingTest {

    private static final String SERVICE = "securityhub";

    @Test
    void theJsonEncoderFormatsAnEventWithTheLogbackVersionWeShip() {
        LoggerContext context = new LoggerContext();
        LogstashEncoder encoder = newEncoder(context);

        Logger logger = context.getLogger("com.securityhub.auth.AuthService");
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, Level.INFO,
                "Empresa 7 criada com administrador 9", null, null);
        event.setThreadName("http-nio-8080-exec-1");
        event.setMDCPropertyMap(Collections.singletonMap("traceId", "d7f1a0c2-trace"));

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(json).startsWith("{").endsWith(System.lineSeparator());
        assertThat(json).contains("\"@timestamp\"");
        assertThat(json).contains("\"level\":\"INFO\"");
        assertThat(json).contains("\"logger_name\":\"com.securityhub.auth.AuthService\"");
        assertThat(json).contains("\"message\":\"Empresa 7 criada com administrador 9\"");
        // traceId as a top-level field is what makes it possible to correlate a whole request in
        // the collector; if it goes back to being a nested object, the collector's filter stops
        // finding it.
        assertThat(json).contains("\"traceId\":\"d7f1a0c2-trace\"");
        assertThat(json).contains("\"service\":\"" + SERVICE + "\"");
        // Once only. The <springProperty> of the XML writes the service name into the Logback
        // context properties, and with includeContext turned on it would come out again as
        // `serviceName` on every line — the same datum under two names, forever.
        assertThat(json).doesNotContain("serviceName");
    }

    @Test
    void anExceptionIsSerialisedByTheShortenedConverter() {
        LoggerContext context = new LoggerContext();
        LogstashEncoder encoder = newEncoder(context);

        Logger logger = context.getLogger("com.securityhub.mail.MailService");
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, Level.ERROR,
                "Falha ao entregar a mensagem", new IllegalStateException("SMTP indisponível",
                new java.net.ConnectException("recusada")), null);
        event.setThreadName("mail-1");

        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(json).contains("\"stack_trace\"");
        assertThat(json).contains("SMTP indisponível");
        // rootCauseFirst: the root cause is what is read first, and it is what says what happened.
        assertThat(json).contains("ConnectException");
        // A log line is one line: a stack trace with literal breaks would split the JSON into
        // several entries in the collector.
        assertThat(json.trim()).doesNotContain("\n");
    }

    /**
     * The one weak point of a test that builds the encoder by hand is the configuration diverging
     * from the one the XML declares. This check closes that: if someone changes the encoder, the
     * time zone or the allowed MDC key in {@code logback-spring.xml}, the test above starts
     * measuring something else — and this one says so.
     */
    @Test
    void theConfigurationHereMatchesLogbackSpringXml() {
        String xml = readResource("logback-spring.xml");

        assertThat(xml).contains("net.logstash.logback.encoder.LogstashEncoder");
        assertThat(xml).contains("<timeZone>UTC</timeZone>");
        assertThat(xml).contains("<includeMdcKeyName>traceId</includeMdcKeyName>");
        assertThat(xml).contains("\"service\"");
        assertThat(xml).contains("net.logstash.logback.stacktrace.ShortenedThrowableConverter");
        assertThat(xml).contains("<rootCauseFirst>true</rootCauseFirst>");
        assertThat(xml).contains("<includeContext>false</includeContext>");

        // The file name is part of the configuration: in `logback.xml` the <springProfile> tags
        // would be inert and every profile would fall into the same appender, silently.
        assertThat(getClass().getClassLoader().getResource("logback.xml"))
                .as("a configuração precisa ser logback-spring.xml, nunca logback.xml")
                .isNull();

        // And no file appender: inside a container it is the runtime that collects the log.
        assertThat(xml).doesNotContain("FileAppender");
    }

    private LogstashEncoder newEncoder(LoggerContext context) {
        ShortenedThrowableConverter throwableConverter = new ShortenedThrowableConverter();
        throwableConverter.setRootCauseFirst(true);
        throwableConverter.setMaxDepthPerThrowable(30);
        throwableConverter.setMaxLength(8192);

        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.setTimeZone("UTC");
        encoder.setIncludeContext(false);
        encoder.setIncludeMdc(true);
        encoder.addIncludeMdcKeyName("traceId");
        encoder.setCustomFields("{\"service\":\"" + SERVICE + "\"}");
        encoder.setThrowableConverter(throwableConverter);
        encoder.start();
        return encoder;
    }

    private String readResource(String name) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(stream).as(name).isNotNull();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
