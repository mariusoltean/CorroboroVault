package ro.corroboro.vault.core.services.registration;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.corroboro.vault.database.entity.AppointmentRegistration;
import ro.corroboro.vault.database.entity.RegistrationStatus;
import ro.corroboro.vault.database.repository.AppointmentRegistrationRepository;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegistrationServiceImpl implements RegistrationService {

    private final AppointmentRegistrationRepository repository;

    @Value("${vault.retention.days:15}")
    private int retentionDays;

    @Override
    @Transactional
    public AppointmentRegistration registerOrUpdate(UUID clinicUuid, Integer appointmentId, OffsetDateTime appointmentDate) {
        AppointmentRegistration registration = repository.findByClinicUuidAndAppointmentId(clinicUuid, appointmentId)
                .orElseGet(() -> AppointmentRegistration.builder()
                        .clinicUuid(clinicUuid)
                        .appointmentId(appointmentId)
                        .build());
        registration.setAppointmentDate(appointmentDate);
        registration.setDeleteAfter(appointmentDate.plusDays(retentionDays));
        registration.setStatus(RegistrationStatus.ACTIVE);
        return repository.save(registration);
    }

    @Override
    @Transactional
    public AppointmentRegistration updateAppointmentDate(UUID clinicUuid, Integer appointmentId, OffsetDateTime appointmentDate) {
        AppointmentRegistration registration = require(clinicUuid, appointmentId);
        registration.setAppointmentDate(appointmentDate);
        registration.setDeleteAfter(appointmentDate.plusDays(retentionDays));
        return repository.save(registration);
    }

    @Override
    @Transactional
    public void cancel(UUID clinicUuid, Integer appointmentId) {
        AppointmentRegistration registration = require(clinicUuid, appointmentId);
        registration.setStatus(RegistrationStatus.CANCELLED);
        repository.save(registration);
    }

    @Override
    public AppointmentRegistration require(UUID clinicUuid, Integer appointmentId) {
        return repository.findByClinicUuidAndAppointmentId(clinicUuid, appointmentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No vault registration for clinic=" + clinicUuid + " appointment=" + appointmentId));
    }

    @Override
    public AppointmentRegistration requireActive(UUID clinicUuid, Integer appointmentId) {
        AppointmentRegistration registration = require(clinicUuid, appointmentId);
        if (registration.getStatus() != RegistrationStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Registration is " + registration.getStatus() + " — uploads are no longer accepted");
        }
        return registration;
    }
}
