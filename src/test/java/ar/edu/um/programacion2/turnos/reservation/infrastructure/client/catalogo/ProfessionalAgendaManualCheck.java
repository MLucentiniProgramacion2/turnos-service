package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogException;
import ar.edu.um.programacion2.turnos.security.ServiceTokenIssuer;
import ar.edu.um.programacion2.turnos.security.ServiceTokenProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.AssertionFailedError;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Comprobación manual: la agenda de un profesional contra el catálogo <strong>local</strong>, con un token de servicio
 * firmado como lo firma turnos. <strong>Nunca corre en el CI</strong>: el nombre no coincide con los patrones de surefire
 * ni de failsafe y, además, exige {@code -Dcatalogo.manual=true}.
 * <p>
 * Con el catálogo levantado y {@code CATALOGO_API_URL} y {@code JWT_BASE64_SECRET} en el {@code .env} (la misma clave que
 * el catálogo):
 *
 * <pre>./mvnw test -Dtest=ProfessionalAgendaManualCheck -Dcatalogo.manual=true [-Dprofesional=201]</pre>
 *
 * Imprime la versión del catálogo, el profesional y sus horarios. Nunca imprime el token ni la clave.
 */
@EnabledIfSystemProperty(named = "catalogo.manual", matches = "true")
class ProfessionalAgendaManualCheck {

    @Test
    void pideLaAgendaAlCatalogoLocal() throws IOException {
        Properties dotEnv = readDotEnv();
        long profesional = Long.parseLong(System.getProperty("profesional", "201"));
        CatalogoApiProperties properties = new CatalogoApiProperties();
        properties.setUrl(URI.create(required("CATALOGO_API_URL", dotEnv)));
        byte[] clave = Base64.from(required("JWT_BASE64_SECRET", dotEnv)).decode();
        ServiceTokenIssuer emisor = new ServiceTokenIssuer(
            new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(clave, "HmacSHA512"))),
            new ServiceTokenProperties()
        );
        CatalogoAgendaClient client = new CatalogoAgendaClient(new CatalogoClientConfiguration().catalogoRestClient(properties), emisor);

        Optional<ProfessionalAgenda> agenda;
        try {
            agenda = client.fetchAgenda(profesional, "comprobacion.manual");
        } catch (CatalogException e) {
            throw oneLineFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (agenda.isEmpty()) {
            System.out.println("profesional=" + profesional + ": el catálogo no lo conoce (404 PROFESSIONAL_NOT_FOUND)");
            return;
        }
        ProfessionalAgenda a = agenda.orElseThrow();
        System.out.println(
            "catalogVersion=" +
            a.catalogVersion() +
            " profesional=" +
            a.professional().id() +
            " " +
            a.professional().firstName() +
            " " +
            a.professional().lastName() +
            " habilitado=" +
            a.professional().enabled() +
            " categoría=" +
            a.professional().category().name() +
            " horarios=" +
            a.weeklySchedules().size()
        );
        a
            .weeklySchedules()
            .forEach(h ->
                System.out.println("  " + h.id() + " " + h.dayOfWeek() + " " + h.startTime() + "-" + h.endTime() + " cada " + h.slotDurationMinutes() + " min")
            );
    }

    private static Properties readDotEnv() throws IOException {
        Properties dotEnv = new Properties();
        Path path = Path.of(".env");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                dotEnv.load(reader);
            }
        }
        return dotEnv;
    }

    private static String required(String name, Properties dotEnv) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = dotEnv.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            throw oneLineFailure("Falta " + name + " en el .env o en el entorno");
        }
        return value.strip();
    }

    private static AssertionFailedError oneLineFailure(String message) {
        System.out.println("FALLÓ " + message);
        AssertionFailedError failure = new AssertionFailedError(message);
        failure.setStackTrace(new StackTraceElement[0]);
        return failure;
    }
}
