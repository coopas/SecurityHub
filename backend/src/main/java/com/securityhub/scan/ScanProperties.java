package com.securityhub.scan;

import javax.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code securityhub.scan.*}, mirroring {@code AttachmentProperties}.
 *
 * <p>{@code max-upload-bytes} has been sitting in {@code application.yml} since the project
 * began with nothing reading it. This class is what finally does, together with the two keys
 * the importer needed and the attachments module has no equivalent of.
 *
 * <p>Picked up by the {@code @ConfigurationPropertiesScan} on {@code SecurityHubApplication}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.scan")
public class ScanProperties {

    /**
     * Where the uploaded reports live, and deliberately not the attachments directory.
     *
     * <p>Two reasons. The {@code content_type} CHECK of V8 admits four media types and none of
     * them is the XML or JSON a scanner emits, so a report could not be an attachment row even
     * if the bytes shared the volume. And the retention story is the opposite one: the file
     * behind a discarded import is garbage and is deleted with it, while an attachment is
     * evidence a person deliberately hung on a finding and is kept until they remove it.
     * Sharing a directory would make every cleanup rule of either feature reach files it does
     * not own.
     *
     * <p>Resolved to an absolute, normalized real path once, at startup, by
     * {@link ScanFileStorage}; a relative value here is relative to the working directory of
     * the process, which is why the container sets an absolute one.
     */
    private String directory = "./uploads-scan";

    /**
     * Application-level ceiling for one report, separate from
     * {@code spring.servlet.multipart.max-file-size} for the same reason
     * {@code securityhub.attachments.max-size-bytes} is: the container limit protects the
     * process, this one is the product rule and may be lowered without touching the container.
     */
    @Min(1)
    private long maxUploadBytes = 10_485_760L;

    /**
     * Hard ceiling on how many findings one import may stage, and the reason the import can
     * stay synchronous at all.
     *
     * <p>The upload parses, matches and writes inside the request thread. That is the right
     * shape for a report of a few hundred findings and the wrong shape for one of fifty
     * thousand, so instead of a job store, a status endpoint and a polling client, there is
     * this number and a 400 that says what to do about it. A limit that turns an unbounded
     * request into a refusal is a smaller thing to own than an asynchronous pipeline.
     */
    @Min(1)
    private int maxFindings = 2000;
}
