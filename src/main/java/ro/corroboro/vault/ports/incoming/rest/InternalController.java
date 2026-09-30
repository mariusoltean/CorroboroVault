package ro.corroboro.vault.ports.incoming.rest;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ro.corroboro.vault.core.domain.request.RegisterAppointmentRequestModelV1;
import ro.corroboro.vault.core.domain.request.UpdateAppointmentDateRequestModelV1;
import ro.corroboro.vault.core.domain.response.RegistrationResponseModelV1;
import ro.corroboro.vault.core.domain.response.UploadedFileResponseModelV1;
import ro.corroboro.vault.core.services.purge.PurgeResult;
import ro.corroboro.vault.core.services.purge.PurgeScheduler;
import ro.corroboro.vault.core.services.registration.RegistrationService;
import ro.corroboro.vault.core.services.storage.FileStorageService;
import ro.corroboro.vault.core.services.upload.UploadService;
import ro.corroboro.vault.database.entity.AppointmentRegistration;
import ro.corroboro.vault.database.entity.UploadedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The vault's entire API surface. Every endpoint here is gated by
 * {@link ro.corroboro.vault.config.ServiceTokenAuthFilter} — SuperMed's backend is the
 * only caller, reached only over the internal Docker network in every real deployment.
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final RegistrationService registrationService;
    private final UploadService uploadService;
    private final FileStorageService fileStorageService;
    private final PurgeScheduler purgeScheduler;

    @Value("${vault.purge.manual-trigger-enabled:false}")
    private boolean manualPurgeTriggerEnabled;

    // ─── Registrations ──────────────────────────────────────────────────────

    @PostMapping("/registrations")
    public ResponseEntity<RegistrationResponseModelV1> register(@Valid @RequestBody RegisterAppointmentRequestModelV1 request) {
        AppointmentRegistration registration = registrationService.registerOrUpdate(
                request.getClinicUuid(), request.getAppointmentId(), request.getAppointmentDate());
        return ResponseEntity.ok(toResponse(registration));
    }

    @PutMapping("/registrations/{clinicUuid}/{appointmentId}")
    public ResponseEntity<RegistrationResponseModelV1> updateAppointmentDate(
            @PathVariable UUID clinicUuid,
            @PathVariable Integer appointmentId,
            @Valid @RequestBody UpdateAppointmentDateRequestModelV1 request) {
        AppointmentRegistration registration = registrationService.updateAppointmentDate(
                clinicUuid, appointmentId, request.getAppointmentDate());
        return ResponseEntity.ok(toResponse(registration));
    }

    @DeleteMapping("/registrations/{clinicUuid}/{appointmentId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID clinicUuid, @PathVariable Integer appointmentId) {
        registrationService.cancel(clinicUuid, appointmentId);
        return ResponseEntity.noContent().build();
    }

    // ─── Files ──────────────────────────────────────────────────────────────

    @PostMapping(value = "/registrations/{clinicUuid}/{appointmentId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadedFileResponseModelV1> upload(
            @PathVariable UUID clinicUuid,
            @PathVariable Integer appointmentId,
            @RequestParam("file") MultipartFile file) throws IOException {
        UploadedFile saved = uploadService.upload(
                clinicUuid, appointmentId, file.getOriginalFilename(), file.getContentType(), file.getInputStream());
        log.info("Vault upload accepted: clinic={} appointment={} file={} size={} scan={}",
                clinicUuid, appointmentId, saved.getId(), saved.getSizeBytes(), saved.getClamScanResult());
        return ResponseEntity.ok(toResponse(saved));
    }

    @GetMapping("/registrations/{clinicUuid}/{appointmentId}/files")
    public ResponseEntity<List<UploadedFileResponseModelV1>> list(
            @PathVariable UUID clinicUuid, @PathVariable Integer appointmentId) {
        List<UploadedFileResponseModelV1> files = uploadService.list(clinicUuid, appointmentId)
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(files);
    }

    @DeleteMapping("/registrations/{clinicUuid}/{appointmentId}/files/{fileId}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable UUID clinicUuid, @PathVariable Integer appointmentId, @PathVariable UUID fileId) {
        uploadService.delete(clinicUuid, appointmentId, fileId);
        return ResponseEntity.noContent().build();
    }

    /** Range-aware single-file download — supports pause/resume for multi-hundred-MB files. */
    @GetMapping("/registrations/{clinicUuid}/{appointmentId}/files/{fileId}")
    public ResponseEntity<ResourceRegion> download(
            @PathVariable UUID clinicUuid,
            @PathVariable Integer appointmentId,
            @PathVariable UUID fileId,
            @RequestHeader HttpHeaders headers) throws IOException {

        UploadedFile file = uploadService.get(clinicUuid, appointmentId, fileId);
        Path path = fileStorageService.resolve(file.getStoredPath());
        UrlResource resource = new UrlResource(path.toUri());
        long contentLength = resource.contentLength();

        MediaType mediaType = resolveMediaType(file);
        String disposition = ContentDisposition.attachment()
                .filename(file.getOriginalFilename(), StandardCharsets.UTF_8)
                .build().toString();

        List<HttpRange> ranges = headers.getRange();
        HttpStatus status;
        ResourceRegion region;
        if (ranges.isEmpty()) {
            region = new ResourceRegion(resource, 0, contentLength);
            status = HttpStatus.OK;
        } else {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(end - start + 1, contentLength - start);
            region = new ResourceRegion(resource, start, rangeLength);
            status = HttpStatus.PARTIAL_CONTENT;
        }

        return ResponseEntity.status(status)
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }

    /**
     * All files for this registration as one zip, streamed on the fly. Not Range-resumable
     * (see the plan's "Bulk download: zip-all" note) — the doctor retries or falls back to
     * downloading files individually if this is interrupted.
     */
    @GetMapping("/registrations/{clinicUuid}/{appointmentId}/files/zip")
    public void downloadZip(
            @PathVariable UUID clinicUuid,
            @PathVariable Integer appointmentId,
            HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"appointment-" + appointmentId + "-documents.zip\"");
        uploadService.writeAllAsZip(clinicUuid, appointmentId, response.getOutputStream());
    }

    // ─── Dev/acceptance-only manual purge trigger — never present in prod ────

    @PostMapping("/admin/purge")
    public ResponseEntity<PurgeResult> triggerPurge() {
        if (!manualPurgeTriggerEnabled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(purgeScheduler.purgeNow());
    }

    /** The stored mimeType (from the original upload) is the source of truth when present;
     *  MediaTypeFactory takes a FILENAME to guess from, not a mime-type string, so it's
     *  only consulted as a fallback for older rows that never recorded one. */
    private MediaType resolveMediaType(UploadedFile file) {
        if (file.getMimeType() != null && !file.getMimeType().isBlank()) {
            try {
                return MediaType.parseMediaType(file.getMimeType());
            } catch (org.springframework.util.InvalidMimeTypeException ignored) {
                // fall through to filename-based guessing below
            }
        }
        return MediaTypeFactory.getMediaType(file.getOriginalFilename()).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    // ─── Mapping helpers ──────────────────────────────────────────────────

    private RegistrationResponseModelV1 toResponse(AppointmentRegistration registration) {
        return new RegistrationResponseModelV1(
                registration.getClinicUuid(),
                registration.getAppointmentId(),
                registration.getAppointmentDate(),
                registration.getDeleteAfter(),
                registration.getStatus().name());
    }

    private UploadedFileResponseModelV1 toResponse(UploadedFile file) {
        return new UploadedFileResponseModelV1(
                file.getId(),
                file.getOriginalFilename(),
                file.getSizeBytes(),
                file.getMimeType(),
                file.getClamScanResult().name(),
                file.getUploadedAt());
    }
}
