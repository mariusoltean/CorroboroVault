package ro.corroboro.vault.core.services.registration;

import ro.corroboro.vault.database.entity.AppointmentRegistration;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface RegistrationService {

    /** Creates the registration, or re-activates/updates it if one already exists (idempotent). */
    AppointmentRegistration registerOrUpdate(UUID clinicUuid, Integer appointmentId, OffsetDateTime appointmentDate);

    /** Reschedule: updates the stored appointment date (and therefore deleteAfter) only. */
    AppointmentRegistration updateAppointmentDate(UUID clinicUuid, Integer appointmentId, OffsetDateTime appointmentDate);

    /** Cancellation: blocks further uploads immediately but does not touch deleteAfter. */
    void cancel(UUID clinicUuid, Integer appointmentId);

    /** @throws java.util.NoSuchElementException if no registration exists for this pair. */
    AppointmentRegistration require(UUID clinicUuid, Integer appointmentId);

    /** @throws IllegalStateException if the registration exists but is CANCELLED. */
    AppointmentRegistration requireActive(UUID clinicUuid, Integer appointmentId);
}
