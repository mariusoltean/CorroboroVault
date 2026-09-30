package ro.corroboro.vault.core.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registers (or updates, on reschedule) the appointment a set of uploaded documents
 * belongs to. Sent by SuperMed's backend only — the vault never learns patient identity.
 */
@Data
public class RegisterAppointmentRequestModelV1 {

    @NotNull(message = "clinicUuid is required")
    private UUID clinicUuid;

    @NotNull(message = "appointmentId is required")
    private Integer appointmentId;

    /** The appointment's own start date/time — deleteAfter is computed from this. */
    @NotNull(message = "appointmentDate is required")
    private OffsetDateTime appointmentDate;
}
