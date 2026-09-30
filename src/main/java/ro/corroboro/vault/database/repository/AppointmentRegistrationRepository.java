package ro.corroboro.vault.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ro.corroboro.vault.database.entity.AppointmentRegistration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRegistrationRepository extends JpaRepository<AppointmentRegistration, UUID> {

    Optional<AppointmentRegistration> findByClinicUuidAndAppointmentId(UUID clinicUuid, Integer appointmentId);

    List<AppointmentRegistration> findByDeleteAfterBefore(OffsetDateTime cutoff);
}
