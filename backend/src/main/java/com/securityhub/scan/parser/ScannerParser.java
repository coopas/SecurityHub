package com.securityhub.scan.parser;

import com.securityhub.scan.ScanFinding;
import com.securityhub.scan.ScanFormat;
import com.securityhub.scan.ScanParseException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads one scanner's report into the canonical finding contract.
 *
 * <p>There is no registry and no factory: the import service injects {@code
 * List<ScannerParser>} and picks the one whose {@link #format()} matches the format the
 * caller declared. Adding a scanner is adding a {@code @Component} and nothing else.
 *
 * <p>Implementations never touch the database, never resolve assets and never count
 * anything — the import summary belongs to the service.
 */
public interface ScannerParser {

    ScanFormat format();

    /**
     * @param input the report bytes; the caller owns the stream and closes it
     * @return every finding worth importing, in report order, possibly empty
     * @throws ScanParseException when the input is blank or its syntax breaks outright. An
     *                            individual entry that cannot be read is skipped instead:
     *                            one odd entry must not cost the other four hundred.
     */
    List<ScanFinding> parse(InputStream input);
}
