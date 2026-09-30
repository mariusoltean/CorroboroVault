package ro.corroboro.vault.core.services.purge;

public record PurgeResult(int registrationsPurged, int filesDeleted, int errors) {
}
