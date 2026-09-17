package com.securityhub.attachment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Not a JSON payload: it carries the three things the controller needs to build the download
 * response out of the transaction, so that the lazy associations of the entity are never
 * touched after it closes.
 */
@Getter
@AllArgsConstructor
public class AttachmentDownload {

    private final String filename;
    private final String contentType;
    private final byte[] content;
}
