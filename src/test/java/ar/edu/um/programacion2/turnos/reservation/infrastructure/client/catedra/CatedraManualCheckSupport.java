package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.opentest4j.AssertionFailedError;
import org.springframework.web.client.RestClient;

/**
 * Lo que comparten las comprobaciones manuales contra la cátedra real: leer {@code CATEDRA_API_URL} y {@code CATEDRA_JWT}
 * del entorno o del {@code .env} (sin imprimirlos nunca) y fallar con una sola línea, sin stack trace ni causa, que podrían
 * llevar la URL.
 */
final class CatedraManualCheckSupport {

    private CatedraManualCheckSupport() {}

    static RestClient restClient() throws IOException {
        Properties dotEnv = readDotEnv();
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(URI.create(required("CATEDRA_API_URL", dotEnv)));
        properties.setToken(required("CATEDRA_JWT", dotEnv));
        return new CatedraClientConfiguration().catedraRestClient(properties);
    }

    static Properties readDotEnv() throws IOException {
        Properties dotEnv = new Properties();
        Path path = Path.of(".env");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                dotEnv.load(reader);
            }
        }
        return dotEnv;
    }

    static String required(String name, Properties dotEnv) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = dotEnv.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            throw oneLineFailure("Falta " + name + " en el .env o en el entorno");
        }
        return value.strip();
    }

    static AssertionFailedError oneLineFailure(String message) {
        System.out.println("FALLÓ " + message);
        AssertionFailedError failure = new AssertionFailedError(message);
        failure.setStackTrace(new StackTraceElement[0]);
        return failure;
    }
}
