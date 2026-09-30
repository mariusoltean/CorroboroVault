package ro.corroboro.vault.core.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadedFileResponseModelV1 {
    private UUID id;
    private String filename;
    private long sizeBytes;
    private String mimeType;
    private String scanResult;
    private OffsetDateTime uploadedAt;
}
