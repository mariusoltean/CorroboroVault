package ro.corroboro.vault.core.domain.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

/** Reschedule: the path already identifies (clinicUuid, appointmentId). */
@Data
public class UpdateAppointmentDateRequestModelV1 {

    @NotNull(message = "appointmentDate is required")
    private OffsetDateTime appointmentDate;
}
