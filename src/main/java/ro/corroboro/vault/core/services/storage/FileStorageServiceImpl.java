package ro.corroboro.vault.core.services.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    @Value("${vault.storage.root-path}")
    private String rootPath;

    private Path root() {
        return Path.of(rootPath).toAbsolutePath().normalize();
    }

    private Path tempDir() throws IOException {
        Path dir = root().resolve(".tmp");
        Files.createDirectories(dir);
        return dir;
    }

    @Override
    public String buildRelativePath(UUID clinicUuid, Integer appointmentId, UUID fileId, String originalFilename) {
        String extension = extensionOf(originalFilename);
        String filename = extension.isEmpty() ? fileId.toString() : fileId + "." + extension;
        return clinicUuid + "/" + appointmentId + "/" + filename;
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) return "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) return "";
        // Keep only safe filename characters — an extension is never a path, but a
        // maliciously crafted "name" could still smuggle slashes/dots through here.
        return originalFilename.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    @Override
    public Path resolve(String relativePath) {
        Path resolved = root().resolve(relativePath).normalize();
        if (!resolved.startsWith(root())) {
            throw new IllegalArgumentException("Resolved path escapes the storage root: " + relativePath);
        }
        return resolved;
    }

    @Override
    public Path writeToTempFile(InputStream content) throws IOException {
        Path tempFile = tempDir().resolve(UUID.randomUUID() + ".tmp");
        try (content) {
            Files.copy(content, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    @Override
    public void moveIntoPlace(Path tempFile, String relativePath) throws IOException {
        Path target = resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            log.warn("FileStorageService: failed to delete {}: {}", relativePath, e.getMessage());
        }
    }

    @Override
    public void deleteAndPruneEmptyDirs(String relativePath) {
        Path file = resolve(relativePath);
        delete(relativePath);
        Path dir = file.getParent();
        Path rootDir = root();
        try {
            while (dir != null && !dir.equals(rootDir) && dir.startsWith(rootDir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.findAny().isPresent()) break;
                }
                Files.delete(dir);
                dir = dir.getParent();
            }
        } catch (IOException e) {
            log.debug("FileStorageService: could not prune empty dirs above {}: {}", relativePath, e.getMessage());
        }
    }
}
