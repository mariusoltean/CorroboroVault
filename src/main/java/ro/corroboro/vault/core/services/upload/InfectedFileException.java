package ro.corroboro.vault.core.services.upload;

/** Thrown when ClamAV flags an uploaded file as infected. Never written to disk. */
public class InfectedFileException extends RuntimeException {
    public InfectedFileException(String message) {
        super(message);
    }
}
