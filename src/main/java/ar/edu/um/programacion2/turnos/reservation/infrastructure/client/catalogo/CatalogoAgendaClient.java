package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import ar.edu.um.programacion2.turnos.config.Constants;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import ar.edu.um.programacion2.turnos.security.ServiceTokenIssuer;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de salida: {@code GET /api/service/professionals/{id}/agenda} del catálogo (contrato del #35 del catálogo).
 * Un solo intento, sin reintentos (DPT-13).
 * <ul>
 * <li>{@code Authorization}: un token técnico de servicio nuevo ({@link ServiceTokenIssuer}). Nunca el JWT del usuario
 * ni el de la cátedra.</li>
 * <li>{@code X-User-Login}: el login del usuario, solo para el log del catálogo. Se manda si tiene de 3 a 50 caracteres
 * de los que admite el login de turnos ({@code Constants.LOGIN_REGEX}); si no, no se manda.</li>
 * </ul>
 * Respuestas: {@code 200} es la agenda (con el profesional deshabilitado, sin horarios); {@code 404
 * PROFESSIONAL_NOT_FOUND} es "no existe" (vacío); {@code 503}, otro {@code 5xx}, {@code 408}, {@code 429} o una falla de
 * red es "no disponible" (con el {@code Retry-After}, si vino); {@code 401} o {@code 403} es un error de configuración
 * nuestro; el resto es "respuesta inválida".
 */
@Component
public class CatalogoAgendaClient implements ProfessionalAgendaProvider {

    static final String PATH = "/api/service/professionals/{id}/agenda";
    static final String USER_LOGIN_HEADER = "X-User-Login";

    /** El tope del {@code Retry-After} que se acepta: más que eso es un valor raro, y se recorta. */
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    private static final Logger LOG = LoggerFactory.getLogger(CatalogoAgendaClient.class);
    private static final Pattern LOGIN = Pattern.compile(Constants.LOGIN_REGEX);
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,9}");
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private final RestClient restClient;
    private final ServiceTokenIssuer serviceTokenIssuer;

    public CatalogoAgendaClient(
        @Qualifier(CatalogoClientConfiguration.CATALOGO_REST_CLIENT) RestClient restClient,
        ServiceTokenIssuer serviceTokenIssuer
    ) {
        this.restClient = restClient;
        this.serviceTokenIssuer = serviceTokenIssuer;
    }

    @Override
    public Optional<ProfessionalAgenda> fetchAgenda(long professionalId, String requestedBy) {
        RestClient.RequestHeadersSpec<?> request = restClient
            .get()
            .uri(PATH, professionalId)
            .headers(headers -> {
                headers.setBearerAuth(serviceTokenIssuer.issue());
                if (isValidLogin(requestedBy)) {
                    headers.set(USER_LOGIN_HEADER, requestedBy);
                } else if (requestedBy != null) {
                    LOG.debug("X-User-Login no se manda: el login no tiene un formato válido");
                }
            });
        byte[] body;
        try {
            body = request.exchange((req, response) -> readBody(response));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("El catálogo no respondió al pedir la agenda ({})", cause.getClass().getSimpleName());
            throw new CatalogUnavailableException("El catálogo no respondió al pedir la agenda", e);
        }
        if (body == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(CatalogoAgendaMapper.toDomain(JSON.readValue(body, CatalogoAgendaDtos.AgendaResponse.class), professionalId));
        } catch (JacksonException e) {
            LOG.warn("Agenda inválida: {}", e.getOriginalMessage());
            throw new CatalogInvalidResponseException("Agenda inválida: el cuerpo no es el JSON esperado", e);
        } catch (IllegalArgumentException e) {
            throw new CatalogInvalidResponseException("Agenda inválida: " + e.getMessage(), e);
        }
    }

    /** El cuerpo de un {@code 200}, o {@code null} si el profesional no existe; cualquier otra respuesta lanza. */
    private static byte[] readBody(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return response.getBody().readAllBytes();
        }
        String code = errorCode(response);
        if (status == 404 && "PROFESSIONAL_NOT_FOUND".equals(code)) {
            return null;
        }
        String description = "El catálogo respondió " + status + (code == null ? " (sin code)" : "") + " al pedir la agenda";
        throw classify(status, description, response.getHeaders());
    }

    private static CatalogException classify(int status, String description, HttpHeaders headers) {
        if (status == 401 || status == 403) {
            // Error de configuración nuestro (la clave compartida o la vida del token): no se le muestra al usuario (DPT-18).
            LOG.error("{}: el catálogo rechazó el token de servicio; revisar JWT_BASE64_SECRET y la vida del token", description);
            return new CatalogAccessDeniedException(description);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new CatalogUnavailableException(description, retryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        return new CatalogInvalidResponseException(description + " (status inesperado)");
    }

    /**
     * El {@code Retry-After} en segundos, recortado a {@link #MAX_RETRY_AFTER}. El catálogo lo manda en segundos; una
     * fecha HTTP u otro formato se ignora.
     */
    static Duration retryAfter(String header) {
        if (header == null || !DELTA_SECONDS.matcher(header.strip()).matches()) {
            return null;
        }
        Duration value = Duration.ofSeconds(Long.parseLong(header.strip()));
        return value.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : value;
    }

    private static String errorCode(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES);
            CatalogoAgendaDtos.Problem problem = JSON.readValue(body, CatalogoAgendaDtos.Problem.class);
            return problem == null ? null : problem.code();
        } catch (IOException | JacksonException e) {
            return null;
        }
    }

    /** De 3 a 50 caracteres (DPT-20) y con el formato del login de turnos. */
    static boolean isValidLogin(String login) {
        return login != null && login.length() >= 3 && login.length() <= 50 && LOGIN.matcher(login).matches();
    }
}
