package ro.corroboro.vault.core.services.purge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ro.corroboro.vault.core.services.storage.FileStorageService;
import ro.corroboro.vault.database.entity.AppointmentRegistration;
import ro.corroboro.vault.database.entity.UploadedFile;
import ro.corroboro.vault.database.repository.AppointmentRegistrationRepository;
import ro.corroboro.vault.database.repository.UploadedFileRepository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Daily job that hard-deletes every registration (and its files, on disk and in the DB)
 * once past its {@code deleteAfter} instant. Mirrors {@code FeedbackScheduler}'s shape in
 * the main backend: one row's failure is logged and skipped, never aborts the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurgeScheduler {

    private final AppointmentRegistrationRepository registrationRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final FileStorageService fileStorageService;

    @Value("${vault.purge.enabled:true}")
    private boolean purgeEnabled;

    @Scheduled(cron = "${vault.purge.cron:0 30 3 * * *}", zone = "Europe/Bucharest")
    public void runDailyPurge() {
        if (!purgeEnabled) {
            log.info("PurgeScheduler: skipped — vault.purge.enabled is false");
            return;
        }
        purgeNow();
    }

    @Transactional
    public PurgeResult purgeNow() {
        OffsetDateTime now = OffsetDateTime.now();
        List<AppointmentRegistration> due = registrationRepository.findByDeleteAfterBefore(now);

        int registrationsPurged = 0;
        int filesDeleted = 0;
        int errors = 0;

        for (AppointmentRegistration registration : due) {
            try {
                List<UploadedFile> files = uploadedFileRepository.findByRegistration(registration);
                for (UploadedFile file : files) {
                    fileStorageService.deleteAndPruneEmptyDirs(file.getStoredPath());
                    filesDeleted++;
                }
                uploadedFileRepository.deleteAll(files);
                registrationRepository.delete(registration);
                registrationsPurged++;
            } catch (Exception e) {
                errors++;
                log.error("PurgeScheduler: failed to purge registration {} (clinic={}, appointment={}): {}",
                        registration.getId(), registration.getClinicUuid(), registration.getAppointmentId(),
                        e.getMessage(), e);
            }
        }

        log.info("PurgeScheduler: run complete — due={} purged={} filesDeleted={} errors={}",
                due.size(), registrationsPurged, filesDeleted, errors);
        return new PurgeResult(registrationsPurged, filesDeleted, errors);
    }
}
