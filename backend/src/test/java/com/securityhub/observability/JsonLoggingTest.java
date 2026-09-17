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
 * O appender JSON de {@code logback-spring.xml} só é usado pelo perfil {@code prod}, e é
 * justamente por isso que este teste existe.
 *
 * <p>O defeito que ele guarda já aconteceu. Com o encoder na linha 7.3+ e o logback 1.2.12 que
 * o Boot 2.7 gerencia, a aplicação <em>compila</em>, <em>sobe</em> e configura o appender sem
 * uma única mensagem de erro — e morre com {@code NoSuchMethodError:
 * ILoggingEvent.getInstant()} ao formatar o primeiro evento. Ou seja: no primeiro log de
 * produção, e em nenhum momento antes. O perfil {@code demo} e o {@code test} usam o appender
 * de console e nunca tocam esse caminho, então a suíte inteira ficava verde sobre uma
 * aplicação que não conseguia registrar uma linha sequer em produção.
 *
 * <p>Este teste formata um evento de verdade, que é o único momento em que a incompatibilidade
 * aparece. Ele é rápido e não depende de Spring: se as duas linhas de versão divergirem de
 * novo, o {@code mvn test} quebra aqui, no lugar de quebrar no cliente.
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
        // traceId como campo de primeiro nível é o que permite correlacionar uma requisição
        // inteira no coletor; se ele voltar a ser um objeto aninhado, o filtro do coletor para
        // de encontrá-lo.
        assertThat(json).contains("\"traceId\":\"d7f1a0c2-trace\"");
        assertThat(json).contains("\"service\":\"" + SERVICE + "\"");
        // Uma vez só. O <springProperty> do XML grava o nome do serviço nas propriedades do
        // contexto do Logback, e com includeContext ligado ele sairia de novo como
        // `serviceName` em toda linha — o mesmo dado com dois nomes, para sempre.
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
        // rootCauseFirst: a causa raiz é o que se lê primeiro, e é o que diz o que houve.
        assertThat(json).contains("ConnectException");
        // Uma linha de log é uma linha: um stack trace com quebras literais partiria o JSON em
        // várias entradas no coletor.
        assertThat(json.trim()).doesNotContain("\n");
    }

    /**
     * O único ponto fraco de um teste que monta o encoder na mão é a configuração divergir da
     * que o XML declara. Esta verificação fecha isso: se alguém trocar o encoder, o fuso ou a
     * chave de MDC permitida em {@code logback-spring.xml}, o teste acima passa a medir outra
     * coisa — e este aqui avisa.
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

        // O nome do arquivo é parte da configuração: em `logback.xml` as tags <springProfile>
        // seriam inertes e todo perfil cairia no mesmo appender, em silêncio.
        assertThat(getClass().getClassLoader().getResource("logback.xml"))
                .as("a configuração precisa ser logback-spring.xml, nunca logback.xml")
                .isNull();

        // E nenhum appender de arquivo: dentro de um contêiner quem coleta o log é o runtime.
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
