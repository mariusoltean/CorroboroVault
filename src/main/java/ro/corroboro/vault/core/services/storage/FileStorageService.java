package ro.corroboro.vault.core.services.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface FileStorageService {

    /** Builds the relative stored path {@code <clinicUuid>/<appointmentId>/<fileUuid>.<ext>}. */
    String buildRelativePath(UUID clinicUuid, Integer appointmentId, UUID fileId, String originalFilename);

    /** Resolves a relative stored path to an absolute filesystem path under the storage root. */
    Path resolve(String relativePath);

    /** Writes the given stream to a temp file (under the storage root) and returns its path. */
    Path writeToTempFile(InputStream content) throws java.io.IOException;

    /** Atomically moves a temp file into its final stored location, creating parent dirs as needed. */
    void moveIntoPlace(Path tempFile, String relativePath) throws java.io.IOException;

    /** Deletes the file at the given relative path, if present. Never throws on a missing file. */
    void delete(String relativePath);

    /** Deletes the file plus any now-empty parent directories, up to (not including) the storage root. */
    void deleteAndPruneEmptyDirs(String relativePath);
}
