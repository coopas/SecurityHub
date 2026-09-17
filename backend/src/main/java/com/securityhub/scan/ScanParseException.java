package com.securityhub.scan;

import com.securityhub.shared.error.BadRequestException;

/**
 * A report that cannot be read at all: empty input, or a document whose syntax breaks before
 * any finding can be recovered.
 *
 * <p>Extends {@code BadRequestException} because a malformed report is bad input and not a
 * server fault — the uploader picked the file and the uploader is the only one who can fix
 * it. A single unreadable entry inside an otherwise valid report is not this exception: the
 * parsers skip it and keep going.
 */
public class ScanParseException extends BadRequestException {

    public ScanParseException(String message) {
        super(message);
    }
}
