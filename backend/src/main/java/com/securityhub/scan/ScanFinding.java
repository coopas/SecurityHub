package com.securityhub.scan;

import com.securityhub.vulnerability.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * One finding as a scanner reported it, already normalized to what the database accepts.
 *
 * <p>Immutable on purpose: a parser hands the list to the import service, which reads it
 * twice — once to match assets and once to write rows — and neither pass may rewrite what the
 * report said.
 *
 * <p>Every value here is already legal for {@code vulnerabilities}: the title fits 200
 * characters, the description 4000, the CVSS is {@code NUMERIC(3,1)} inside 0.0..10.0 and the
 * CVE satisfies the CHECK of V5. That is not a convenience — the import service builds
 * entities directly, so Bean Validation never runs and an illegal value would only surface as
 * a {@code DataIntegrityViolationException} that the global handler turns into a 409 about a
 * conflict that does not exist.
 */
@Getter
@AllArgsConstructor
public class ScanFinding {

    /** Scanner-side rule identity: NSE script id, ZAP pluginid, nuclei template-id. */
    private final String ruleId;

    private final String title;
    private final String description;
    private final Severity severity;
    private final BigDecimal cvssScore;
    private final String cve;

    /**
     * The host, IP or URL the scanner reported, as reported. The import service matches it
     * against an asset's {@code identifier}; this class makes no attempt to resolve it.
     */
    private final String target;

    private final Instant discoveredAt;

    /**
     * Stable identity of the finding across re-scans, so a second import updates a row
     * instead of creating a duplicate. See {@code ScanNormalizer.fingerprint} for what goes
     * into it, and — more importantly — what deliberately does not.
     */
    private final String fingerprint;
}
