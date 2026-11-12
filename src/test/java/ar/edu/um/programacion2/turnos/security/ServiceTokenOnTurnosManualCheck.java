package ar.edu.um.programacion2.turnos.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Comprobación manual: turnos, corriendo en {@code dev}, rechaza su propio token de servicio en {@code /api/account}
 * ({@code 403}, ADR 0005). Firma el token con {@code JWT_BASE64_SECRET} del {@code .env}, como lo firma turnos, y lo
 * manda. <strong>Nunca imprime el token</strong>: solo el status. No corre en el CI (ver {@code *ManualCheck}).
 *
 * <pre>./mvnw test -Dtest=ServiceTokenOnTurnosManualCheck -Dturnos.manual=true [-Dturnos.url=http://localhost:8080]</pre>
 */
@EnabledIfSystemProperty(named = "turnos.manual", matches = "true")
class ServiceTokenOnTurnosManualCheck {

    @Test
    void turnosRechazaSuTokenDeServicioEnApiAccount() throws IOException, InterruptedException {
        String clave = jwtSecret();
        ServiceTokenIssuer emisor = new ServiceTokenIssuer(
            new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(Base64.from(clave).decode(), "HmacSHA512"))),
            new ServiceTokenProperties()
        );
        URI url = URI.create(System.getProperty("turnos.url", "http://localhost:8080") + "/api/account");
        HttpRequest pedido = HttpRequest.newBuilder(url).header("Authorization", "Bearer " + emisor.issue()).timeout(Duration.ofSeconds(5)).GET().build();

        int status;
        try (HttpClient http = HttpClient.newHttpClient()) {
            status = http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode();
        }

        System.out.println("GET /api/account con un token de servicio: " + status + " (se espera 403)");
        assertThat(status).isEqualTo(403);
    }

    private static String jwtSecret() throws IOException {
        String value = System.getenv("JWT_BASE64_SECRET");
        if (value == null || value.isBlank()) {
            Properties dotEnv = new Properties();
            Path path = Path.of(".env");
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    dotEnv.load(reader);
                }
            }
            value = dotEnv.getProperty("JWT_BASE64_SECRET");
        }
        if (value == null || value.isBlank()) {
            throw new AssertionError("Falta JWT_BASE64_SECRET en el .env o en el entorno");
        }
        return value.strip();
    }
}
