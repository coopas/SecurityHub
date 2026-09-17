package com.securityhub.scan.parser;

import com.securityhub.scan.ScanFinding;
import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import com.securityhub.vulnerability.Severity;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads {@code nmap -oX} output through StAX, which the JDK still ships — only JAXB left
 * Java 11 — so no dependency is added for it.
 *
 * <h2>XXE</h2>
 *
 * <p>The factory is hardened before a single byte is read, and this is not a precaution
 * against a hypothetical document: every nmap report opens with a {@code DOCTYPE} pointing at
 * {@code nmap.dtd} on svn.nmap.org, and an uploaded report is a file someone else produced —
 * which is exactly the threat model where a file:// entity reads {@code /etc/passwd} and ships
 * it back as a finding title. With {@code SUPPORT_DTD} off the parser skips the declaration
 * instead of failing on it, so real reports still parse, and any entity reference in the body
 * is then undeclared and breaks the parse. Billion laughs dies the same way: the entities it
 * needs were never declared, so there is nothing to expand.
 *
 * <p>A factory is built per call rather than cached in a field: {@code XMLInputFactory} is not
 * documented as thread-safe, and a shared instance whose properties another caller could
 * reset is the one way this hardening quietly stops applying.
 *
 * <h2>What is imported</h2>
 *
 * <p>NSE script results only. An open port is not a vulnerability, and a service banner is
 * not one either: importing them would put a few hundred untriageable rows in the backlog for
 * every scan, and the rows people actually need would be lost among them.
 *
 * <h2>Severity</h2>
 *
 * <p>nmap has no severity field, so one is derived from the script output and kept
 * deliberately coarse: output naming a CVE or containing {@code VULNERABLE} is {@link
 * Severity#HIGH}, everything else {@link Severity#MEDIUM}. {@code CRITICAL} is never derived —
 * it drives due dates and escalation, and no scanner that omits severity has said enough to
 * justify it. Nothing is invented for CVSS either: nmap gives no score, so the finding carries
 * none and a human fills it in.
 */
@Slf4j
@Component
public class NmapXmlParser implements ScannerParser {

    private static final String VULNERABLE_MARKER = "VULNERABLE";

    /** An {@code addrtype} whose value is a hardware address, never an asset identifier. */
    private static final String MAC_ADDRESS_TYPE = "mac";

    @Override
    public ScanFormat format() {
        return ScanFormat.NMAP_XML;
    }

    @Override
    public List<ScanFinding> parse(InputStream input) {
        InputStream content = ScanNormalizer.requireContent(input, "nmap");
        List<ScanFinding> findings = new ArrayList<>();
        XMLStreamReader reader = null;
        try {
            reader = hardenedFactory().createXMLStreamReader(content, "UTF-8");
            read(reader, findings);
        } catch (XMLStreamException ex) {
            log.debug("Relatório nmap malformado: {}", ex.getMessage());
            throw new ScanParseException("O relatório nmap enviado não é um XML válido");
        } finally {
            close(reader);
        }
        return findings;
    }

    private static XMLInputFactory hardenedFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * One pass, no document tree: a host's addresses and hostnames always precede its ports in
     * nmap output, so by the time a {@code <script>} is reached its target is already known.
     */
    private void read(XMLStreamReader reader, List<ScanFinding> findings) throws XMLStreamException {
        Instant discoveredAt = Instant.now();
        String address = null;
        String hostname = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String element = reader.getLocalName();
                if ("nmaprun".equals(element)) {
                    discoveredAt = startedAt(attribute(reader, "start"), discoveredAt);
                } else if ("host".equals(element)) {
                    address = null;
                    hostname = null;
                } else if ("address".equals(element)) {
                    if (address == null && !MAC_ADDRESS_TYPE.equalsIgnoreCase(attribute(reader, "addrtype"))) {
                        address = attribute(reader, "addr");
                    }
                } else if ("hostname".equals(element)) {
                    if (hostname == null) {
                        hostname = attribute(reader, "name");
                    }
                } else if ("script".equals(element)) {
                    ScanFinding finding = toFinding(reader, target(hostname, address), discoveredAt);
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "host".equals(reader.getLocalName())) {
                address = null;
                hostname = null;
            }
        }
    }

    /**
     * The hostname wins over the address when nmap resolved one: an asset is far more often
     * registered by name than by the address it happened to answer on during this scan.
     */
    private static String target(String hostname, String address) {
        return hostname != null && !hostname.trim().isEmpty() ? hostname.trim() : address;
    }

    private ScanFinding toFinding(XMLStreamReader reader, String target, Instant discoveredAt) {
        String ruleId = attribute(reader, "id");
        String output = attribute(reader, "output");
        // The script id is the only name nmap gives a result; there is no title field.
        String title = ScanNormalizer.title(ruleId);
        if (title == null) {
            return null;
        }
        String cve = ScanNormalizer.cve(output);
        return new ScanFinding(
                ruleId.trim(),
                title,
                ScanNormalizer.description(output),
                severity(output, cve),
                null,
                cve,
                target,
                discoveredAt,
                ScanNormalizer.fingerprint(ScanFormat.NMAP_XML, ruleId.trim(), target, cve));
    }

    private static Severity severity(String output, String cve) {
        if (cve != null) {
            return Severity.HIGH;
        }
        if (output != null && output.toUpperCase(Locale.ROOT).contains(VULNERABLE_MARKER)) {
            return Severity.HIGH;
        }
        return Severity.MEDIUM;
    }

    /**
     * {@code nmaprun/@start} is epoch seconds and covers the whole run; nmap timestamps no
     * individual script result. A report without it falls back to the import instant, which is
     * at worst late and never wrong in a way that hides a finding.
     */
    private static Instant startedAt(String start, Instant fallback) {
        if (start == null || start.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(start.trim()));
        } catch (NumberFormatException | DateTimeException ex) {
            return fallback;
        }
    }

    private static String attribute(XMLStreamReader reader, String name) {
        return reader.getAttributeValue(null, name);
    }

    private static void close(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ex) {
            log.debug("Falha ao fechar o leitor XML do nmap: {}", ex.getMessage());
        }
    }
}
