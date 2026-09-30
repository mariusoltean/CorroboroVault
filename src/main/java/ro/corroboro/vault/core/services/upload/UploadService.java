package ro.corroboro.vault.core.services.upload;

import ro.corroboro.vault.database.entity.UploadedFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

public interface UploadService {

    /** Scans then stores the file. @throws InfectedFileException if ClamAV flags it. */
    UploadedFile upload(UUID clinicUuid, Integer appointmentId, String originalFilename, String mimeType, InputStream content);

    List<UploadedFile> list(UUID clinicUuid, Integer appointmentId);

    /** @throws java.util.NoSuchElementException if the file doesn't belong to this registration. */
    UploadedFile get(UUID clinicUuid, Integer appointmentId, UUID fileId);

    void delete(UUID clinicUuid, Integer appointmentId, UUID fileId);

    /**
     * Streams every file for this registration into {@code out} as a zip, on the fly.
     * Not Range-resumable by design — see the plan's "Bulk download: zip-all" note.
     */
    void writeAllAsZip(UUID clinicUuid, Integer appointmentId, OutputStream out) throws java.io.IOException;
}
