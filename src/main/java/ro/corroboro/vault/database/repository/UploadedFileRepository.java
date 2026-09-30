package ro.corroboro.vault.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ro.corroboro.vault.database.entity.AppointmentRegistration;
import ro.corroboro.vault.database.entity.UploadedFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, UUID> {

    List<UploadedFile> findByRegistrationOrderByUploadedAtAsc(AppointmentRegistration registration);

    Optional<UploadedFile> findByIdAndRegistration(UUID id, AppointmentRegistration registration);

    List<UploadedFile> findByRegistration(AppointmentRegistration registration);
}
