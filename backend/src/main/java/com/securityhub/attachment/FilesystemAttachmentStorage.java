package com.securityhub.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Flat directory of files named by a server-generated UUID.
 *
 * <p>No sharding into subdirectories: the names are globally unique, nothing ever lists the
 * directory, and a two-level fan-out would only add a way for a path to be assembled wrongly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilesystemAttachmentStorage implements AttachmentStorage {

    /** The same shape the CHECK constraint of V8 enforces on {@code stored_filename}. */
    private static final Pattern STORED_FILENAME = Pattern.compile("^[0-9a-f]{32}$");

    private final AttachmentProperties properties;

    private Path root;

    /**
     * Resolved once, at startup, to an absolute normalized real path. Doing it here rather
     * than per request means the containment check below compares against a path that
     * symbolic links have already been followed through — otherwise a link planted inside the
     * directory could make a contained path point outside it.
     */
    @PostConstruct
    void prepareDirectory() {
        try {
            Path configured = Paths.get(properties.getDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
            log.info("Diretório de anexos em {}", root);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Não foi possível preparar o diretório de anexos " + properties.getDirectory(), ex);
        }
    }

    @Override
    public void store(String storedFilename, byte[] content) {
        Path target = resolve(storedFilename);
        try {
            // CREATE_NEW and not CREATE: an existing file means a UUID collision or a bug, and
            // in either case overwriting silently destroys evidence that belongs to someone.
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível gravar o anexo " + storedFilename, ex);
        }
    }

    @Override
    public byte[] load(String storedFilename) {
        Path target = resolve(storedFilename);
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException ex) {
            // The row exists and the file does not: an inconsistency of the server, not a
            // request the client got wrong, so it is not dressed up as a 404.
            throw new IllegalStateException("Arquivo do anexo " + storedFilename + " não está no disco", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível ler o anexo " + storedFilename, ex);
        }
    }

    @Override
    public boolean delete(String storedFilename) {
        Path target = resolve(storedFilename);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível remover o anexo " + storedFilename, ex);
        }
    }

    @Override
    public Path rootDirectory() {
        return root;
    }

    /**
     * Used by store, load and delete alike — one function, so none of the three can be the one
     * that forgets.
     *
     * <p>The two checks are complementary. The pattern is the real defence: a name of 32 hex
     * characters cannot contain a separator or a dot segment, so traversal is impossible
     * before any path arithmetic happens. The {@code startsWith} assertion is the backstop for
     * the day that pattern is loosened, and it is {@link Path#startsWith(Path)} rather than
     * {@code String.startsWith} on purpose: the former compares whole name elements, so
     * {@code /var/uploads-evil} is correctly <em>not</em> inside {@code /var/uploads}, while
     * the string comparison would happily accept it.
     */
    private Path resolve(String storedFilename) {
        if (storedFilename == null || !STORED_FILENAME.matcher(storedFilename).matches()) {
            throw new IllegalArgumentException("Nome interno de anexo inválido");
        }
        Path target = root.resolve(storedFilename).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("Caminho de anexo fora do diretório configurado");
        }
        return target;
    }
}
