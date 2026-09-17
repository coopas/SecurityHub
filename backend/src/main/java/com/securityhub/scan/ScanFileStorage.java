package com.securityhub.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Flat directory of uploaded scan reports, named by a server-generated UUID.
 *
 * <p>A concrete class and not an interface with one implementation. {@code AttachmentStorage}
 * earns its interface because it is the single door through which a path may be built, and an
 * interface makes that provable; repeating the pattern here would give the codebase a second
 * one-implementation abstraction and prove nothing new. The property that matters is kept the
 * same way: {@link #resolve} is the only place a path is assembled, and store and delete both
 * go through it.
 *
 * <p>The containment check is {@code FilesystemAttachmentStorage}'s, verbatim, and the two
 * halves are complementary. The pattern is the real defence: 32 hex characters cannot contain
 * a separator or a dot segment, so traversal is impossible before any path arithmetic happens.
 * The {@code startsWith} assertion is the backstop for the day that pattern is loosened, and it
 * is {@link Path#startsWith(Path)} rather than {@code String.startsWith} on purpose: the former
 * compares whole name elements, so {@code /var/uploads-evil} is correctly <em>not</em> inside
 * {@code /var/uploads}, while the string comparison would happily accept it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScanFileStorage {

    /** The same shape the CHECK constraint of V9 enforces on {@code stored_filename}. */
    private static final Pattern STORED_FILENAME = Pattern.compile("^[0-9a-f]{32}$");

    private final ScanProperties properties;

    private Path root;

    /**
     * Resolved once, at startup, to an absolute normalized real path. Doing it here rather than
     * per request means the containment check compares against a path that symbolic links have
     * already been followed through — otherwise a link planted inside the directory could make
     * a contained path point outside it.
     */
    @PostConstruct
    void prepareDirectory() {
        try {
            Path configured = Paths.get(properties.getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
            log.info("Diretório de relatórios de scan em {}", root);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Não foi possível preparar o diretório de relatórios de scan "
                            + properties.getDirectory(), ex);
        }
    }

    /**
     * @throws IllegalStateException if a file with that name already exists — the name is a
     *                               fresh UUID, so a collision means something is wrong and
     *                               overwriting would destroy somebody else's report
     */
    public void store(String storedFilename, byte[] content) {
        Path target = resolve(storedFilename);
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Não foi possível gravar o relatório de scan " + storedFilename, ex);
        }
    }

    /** @return {@code true} when a file was actually removed */
    public boolean delete(String storedFilename) {
        Path target = resolve(storedFilename);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Não foi possível remover o relatório de scan " + storedFilename, ex);
        }
    }

    /** The resolved root, for diagnostics and for tests that assert nothing escaped it. */
    public Path rootDirectory() {
        return root;
    }

    private Path resolve(String storedFilename) {
        if (storedFilename == null || !STORED_FILENAME.matcher(storedFilename).matches()) {
            throw new IllegalArgumentException("Nome interno de relatório de scan inválido");
        }
        Path target = root.resolve(storedFilename).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("Caminho de relatório fora do diretório configurado");
        }
        return target;
    }
}
