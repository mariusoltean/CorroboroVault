package ro.corroboro.vault.core.services.clamav;

import java.nio.file.Path;

public interface ClamAvClient {

    /** Streams the given file to clamd over INSTREAM and returns its verdict. */
    ClamAvScanResult scan(Path file);
}
