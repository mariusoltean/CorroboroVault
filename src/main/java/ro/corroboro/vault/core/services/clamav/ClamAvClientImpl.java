package ro.corroboro.vault.core.services.clamav;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Talks to clamd over its INSTREAM protocol (not the {@code clamscan} CLI, which reloads
 * its full virus-definition database on every invocation — far too slow per upload).
 *
 * <p>Protocol: send {@code zINSTREAM\0}, then a sequence of (4-byte big-endian length,
 * chunk bytes) pairs, terminated by a zero-length chunk. clamd replies with a single
 * null-terminated line: {@code stream: OK}, {@code stream: <name> FOUND}, or
 * {@code stream: <message> ERROR}.
 */
@Slf4j
@Component
public class ClamAvClientImpl implements ClamAvClient {

    private static final int CHUNK_SIZE = 8192;

    @Value("${vault.clamav.host}")
    private String host;

    @Value("${vault.clamav.port}")
    private int port;

    @Value("${vault.clamav.timeout-ms:60000}")
    private int timeoutMs;

    @Override
    public ClamAvScanResult scan(Path file) {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            try (InputStream fileIn = Files.newInputStream(file)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = fileIn.read(buffer)) != -1) {
                    out.write(ByteBuffer.allocate(4).putInt(read).array());
                    out.write(buffer, 0, read);
                }
            }
            // Zero-length chunk terminates the stream.
            out.write(new byte[]{0, 0, 0, 0});
            out.flush();

            String response = readNullTerminated(in);
            return parseResponse(response);
        } catch (IOException e) {
            log.error("ClamAvClient: scan failed for {}: {}", file, e.getMessage(), e);
            return ClamAvScanResult.error(e.getMessage());
        }
    }

    private String readNullTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            buf.write(b);
        }
        return buf.toString(StandardCharsets.US_ASCII).trim();
    }

    private ClamAvScanResult parseResponse(String response) {
        if (response.endsWith("OK")) {
            return ClamAvScanResult.clean();
        }
        if (response.endsWith("FOUND")) {
            // "stream: <signature> FOUND" — signature is display-only, never persisted verbatim.
            String signature = response.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
            log.warn("ClamAvClient: infected upload rejected — signature={}", signature);
            return ClamAvScanResult.infected(signature);
        }
        log.error("ClamAvClient: unexpected/error response: {}", response);
        return ClamAvScanResult.error(response);
    }
}
