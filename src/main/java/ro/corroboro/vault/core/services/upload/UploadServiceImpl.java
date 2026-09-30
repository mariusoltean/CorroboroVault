package ro.corroboro.vault.core.services.upload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.corroboro.vault.core.services.clamav.ClamAvClient;
import ro.corroboro.vault.core.services.clamav.ClamAvScanResult;
import ro.corroboro.vault.core.services.registration.RegistrationService;
import ro.corroboro.vault.core.services.storage.FileStorageService;
import ro.corroboro.vault.database.entity.AppointmentRegistration;
import ro.corroboro.vault.database.entity.ScanResult;
import ro.corroboro.vault.database.entity.UploadedFile;
import ro.corroboro.vault.database.repository.UploadedFileRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    private final RegistrationService registrationService;
    private final FileStorageService fileStorageService;
    private final ClamAvClient clamAvClient;
    private final UploadedFileRepository uploadedFileRepository;

    @Value("${vault.clamav.enabled:true}")
    private boolean clamAvEnabled;

    @Override
    @Transactional
    public UploadedFile upload(UUID clinicUuid, Integer appointmentId, String originalFilename,
                                String mimeType, InputStream content) {
        AppointmentRegistration registration = registrationService.requireActive(clinicUuid, appointmentId);

        Path tempFile = stageToTempFile(content);
        long size = sizeOf(tempFile);

        ScanResult scanResult = scan(tempFile);

        UUID fileId = UUID.randomUUID();
        String relativePath = fileStorageService.buildRelativePath(clinicUuid, appointmentId, fileId, originalFilename);
        try {
            fileStorageService.moveIntoPlace(tempFile, relativePath);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }

        UploadedFile uploadedFile = UploadedFile.builder()
                .id(fileId)
                .registration(registration)
                .originalFilename(sanitizeDisplayName(originalFilename))
                .storedPath(relativePath)
                .mimeType(mimeType)
                .sizeBytes(size)
                .clamScanResult(scanResult)
                .build();
        return uploadedFileRepository.save(uploadedFile);
    }

    /** CLEAN/ERROR are both stored (an ERROR just means scanning itself failed, e.g. clamd
     *  briefly unreachable — a doctor still needs the file); only INFECTED is rejected outright. */
    private ScanResult scan(Path tempFile) {
        if (!clamAvEnabled) {
            return ScanResult.CLEAN;
        }
        ClamAvScanResult verdict = clamAvClient.scan(tempFile);
        return switch (verdict.verdict()) {
            case CLEAN -> ScanResult.CLEAN;
            case ERROR -> ScanResult.ERROR;
            case INFECTED -> {
                deleteQuietly(tempFile);
                throw new InfectedFileException("Uploaded file failed the virus scan");
            }
        };
    }

    private Path stageToTempFile(InputStream content) {
        try {
            return fileStorageService.writeToTempFile(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage upload", e);
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("UploadService: failed to delete temp file {}: {}", path, e.getMessage());
        }
    }

    /** Strips any directory components — only ever used for display, never as a real path. */
    private String sanitizeDisplayName(String originalFilename) {
        if (originalFilename == null) return "file";
        return Path.of(originalFilename).getFileName().toString();
    }

    @Override
    public List<UploadedFile> list(UUID clinicUuid, Integer appointmentId) {
        AppointmentRegistration registration = registrationService.require(clinicUuid, appointmentId);
        return uploadedFileRepository.findByRegistrationOrderByUploadedAtAsc(registration);
    }

    @Override
    public UploadedFile get(UUID clinicUuid, Integer appointmentId, UUID fileId) {
        AppointmentRegistration registration = registrationService.require(clinicUuid, appointmentId);
        return uploadedFileRepository.findByIdAndRegistration(fileId, registration)
                .orElseThrow(() -> new NoSuchElementException("No such file " + fileId + " for this appointment"));
    }

    @Override
    @Transactional
    public void delete(UUID clinicUuid, Integer appointmentId, UUID fileId) {
        UploadedFile file = get(clinicUuid, appointmentId, fileId);
        uploadedFileRepository.delete(file);
        fileStorageService.deleteAndPruneEmptyDirs(file.getStoredPath());
    }

    @Override
    public void writeAllAsZip(UUID clinicUuid, Integer appointmentId, OutputStream out) throws IOException {
        List<UploadedFile> files = list(clinicUuid, appointmentId);
        java.util.Set<String> usedNames = new java.util.HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (UploadedFile file : files) {
                String entryName = uniqueEntryName(file.getOriginalFilename(), usedNames);
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(fileStorageService.resolve(file.getStoredPath()), zip);
                zip.closeEntry();
            }
        }
    }

    private String uniqueEntryName(String originalFilename, java.util.Set<String> usedNames) {
        String base = (originalFilename == null || originalFilename.isBlank()) ? "file" : originalFilename;
        String candidate = base;
        int suffix = 1;
        while (!usedNames.add(candidate)) {
            int dot = base.lastIndexOf('.');
            candidate = dot > 0
                    ? base.substring(0, dot) + " (" + suffix + ")" + base.substring(dot)
                    : base + " (" + suffix + ")";
            suffix++;
        }
        return candidate;
    }
}
