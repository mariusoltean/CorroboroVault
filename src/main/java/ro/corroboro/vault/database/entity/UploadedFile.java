package ro.corroboro.vault.database.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single uploaded document. {@code storedPath} identifies the visit
 * (clinicUuid/appointmentId/fileUuid.ext), never the patient — no patient name/email/phone
 * column exists anywhere on this entity or {@link AppointmentRegistration}.
 */
@Entity
@Table(name = "uploaded_file")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadedFile {

    // Self-assigned (not @GeneratedValue) — the caller needs the id before insert, to
    // build storedPath from it.
    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id", nullable = false)
    private AppointmentRegistration registration;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** Path relative to the storage root: {@code <clinicUuid>/<appointmentId>/<fileUuid>.<ext>} */
    @Column(name = "stored_path", nullable = false, unique = true)
    private String storedPath;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "clam_scan_result", nullable = false, length = 16)
    private ScanResult clamScanResult;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime uploadedAt = OffsetDateTime.now();
}
