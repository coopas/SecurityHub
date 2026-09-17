package com.securityhub.attachment;

import java.nio.file.Path;

/**
 * The single door to the bytes on disk.
 *
 * <p>There is one implementation and no S3 adapter behind it; inventing a second backend
 * nobody asked for would be the wrong reason to have this interface. The right reason is that
 * an interface with exactly three operations is the only place a path can be built, so the
 * containment check of {@link FilesystemAttachmentStorage} is provably applied to every read,
 * every write and every delete in the product. A caller that wanted to skip it would have to
 * open a file itself, which is visible in review in a way a forgotten helper call is not.
 */
public interface AttachmentStorage {

    /**
     * @throws IllegalStateException if a file with that name already exists — the name is a
     *                               fresh UUID, so a collision means something is wrong and
     *                               overwriting would destroy somebody else's evidence
     */
    void store(String storedFilename, byte[] content);

    byte[] load(String storedFilename);

    /** @return {@code true} when a file was actually removed */
    boolean delete(String storedFilename);

    /** The resolved root, for diagnostics and for tests that assert nothing escaped it. */
    Path rootDirectory();
}
