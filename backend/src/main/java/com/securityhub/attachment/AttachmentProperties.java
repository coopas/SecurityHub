package com.securityhub.attachment;

import javax.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code securityhub.attachments.*}. The prefix has existed in {@code application.yml}
 * since the first commit, and the compose volume, the {@code SECURITYHUB_ATTACHMENTS_DIR}
 * environment variable and the {@code mkdir} of the Dockerfile have all pointed at it while
 * nothing in the application read it. This class is what finally does.
 *
 * <p>Picked up by the {@code @ConfigurationPropertiesScan} on {@code SecurityHubApplication}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "securityhub.attachments")
public class AttachmentProperties {

    /**
     * Where the files live. Resolved to an absolute, normalized real path once, at startup, by
     * {@link FilesystemAttachmentStorage}; a relative value here is relative to the working
     * directory of the process, which is why the container sets an absolute one.
     */
    private String directory = "./uploads";

    /**
     * Application-level ceiling for one file. It is intentionally separate from
     * {@code spring.servlet.multipart.max-file-size}: the container limit protects the
     * process from a body it would have to buffer, this one is the product rule, and it may
     * be lowered without touching the container.
     */
    @Min(1)
    private long maxSizeBytes = 10_485_760L;
}
