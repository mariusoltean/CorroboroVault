package ro.corroboro.vault.core.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegistrationResponseModelV1 {
    private UUID clinicUuid;
    private Integer appointmentId;
    private OffsetDateTime appointmentDate;
    private OffsetDateTime deleteAfter;
    private String status;
}
