package com.securityhub.scan;

/**
 * The report layouts the importer can read.
 *
 * <p>The format is declared by the caller and never sniffed from the bytes. All three are
 * UTF-8 text, so {@code AttachmentContentTypeDetector} — the only sniffer in this codebase —
 * answers {@code text/plain} for every one of them and cannot tell them apart. Guessing from
 * the first character would be worse than asking: an XML declaration identifies XML but not
 * that the XML came from nmap, and a JSON object at the top of a file could equally be a ZAP
 * report or the first line of a nuclei stream.
 */
public enum ScanFormat {

    /** {@code nmap -oX}: NSE script findings only, never ports or services. */
    NMAP_XML,

    /** OWASP ZAP traditional JSON report: {@code site[] -> alerts[] -> instances[]}. */
    ZAP_JSON,

    /** nuclei {@code -jsonl}: one JSON object per line, no enclosing array. */
    NUCLEI_JSONL
}
