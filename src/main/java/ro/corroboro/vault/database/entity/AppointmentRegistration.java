package ro.corroboro.vault.database.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per appointment SuperMed has registered with the vault. Identity is the
 * (clinicUuid, appointmentId) pair — appointment ids are per-clinic-schema auto-increment
 * values in the main backend, so the same numeric id exists in every clinic for a
 * completely different appointment. Never stores anything patient-identifying.
 */
@Entity
@Table(name = "appointment_registration",
        uniqueConstraints = @UniqueConstraint(columnNames = {"clinic_uuid", "appointment_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clinic_uuid", nullable = false)
    private UUID clinicUuid;

    @Column(name = "appointment_id", nullable = false)
    private Integer appointmentId;

    @Column(name = "appointment_date", nullable = false)
    private OffsetDateTime appointmentDate;

    @Column(name = "delete_after", nullable = false)
    private OffsetDateTime deleteAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RegistrationStatus status = RegistrationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
