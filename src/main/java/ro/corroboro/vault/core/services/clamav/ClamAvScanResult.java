package ro.corroboro.vault.core.services.clamav;

public record ClamAvScanResult(ClamAvVerdict verdict, String detail) {
    public static ClamAvScanResult clean() {
        return new ClamAvScanResult(ClamAvVerdict.CLEAN, null);
    }

    public static ClamAvScanResult infected(String signature) {
        return new ClamAvScanResult(ClamAvVerdict.INFECTED, signature);
    }

    public static ClamAvScanResult error(String message) {
        return new ClamAvScanResult(ClamAvVerdict.ERROR, message);
    }
}
