package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

/**
 * Un pedido a la cátedra y la clasificación de su falla (ADR 0004 de turnos). Un solo intento por llamada.
 * <p>
 * <strong>El cuerpo se lee completo dentro de {@code exchange}</strong> y se parsea aparte: así cualquier falla de red,
 * incluso a mitad del cuerpo, llega como {@link ResourceAccessException}, y nunca se confunde con un JSON roto.
 * <p>
 * <strong>Operaciones con efectos</strong> (crear y confirmar holds): lo único que prueba que no se aplicaron es no haber
 * podido conectar ({@link ConnectException} o {@link HttpConnectTimeoutException}, medido en el #10 con el cliente del
 * JDK). Desde que hay conexión, un timeout es el mismo haya leído el servidor el cuerpo o no, así que cualquier falla es
 * {@link CatedraOutcomeUnknownException} (contrato §18.4). Un {@code 408}, {@code 429} o {@code 5xx} también: el contrato
 * no garantiza que no se haya aplicado nada.
 */
final class CatedraCalls {

    private static final Logger LOG = LoggerFactory.getLogger(CatedraCalls.class);

    /** Lo que se lee del cuerpo de un error para buscar el {@code code}: alcanza para un problem+json. */
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    /** Un {@code code} se escribe en mensajes y logs solo si tiene esta forma (evita inyectar texto en el log). */
    private static final Pattern CODE_FORMAT = Pattern.compile("[A-Z_]{1,64}");

    private CatedraCalls() {}

    /**
     * Ejecuta el pedido y devuelve el cuerpo si la cátedra respondió {@code expectedStatus}.
     *
     * @param operation para los mensajes, por ejemplo "al crear el hold"; nunca la URL ni datos del pedido
     * @param withEffects si la operación cambia algo en la cátedra (decide entre "no disponible" y "resultado desconocido")
     */
    static byte[] call(RestClient.RequestHeadersSpec<?> request, String operation, boolean withEffects, int expectedStatus) {
        try {
            return request.exchange((req, response) -> readBody(response, operation, withEffects, expectedStatus));
        } catch (ResourceAccessException e) {
            // La causa directa es la IOException del cliente del JDK. Solo su tipo va al log: el mensaje de
            // ResourceAccessException lleva la URL.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String causeName = cause.getClass().getSimpleName();
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                LOG.warn("No se pudo conectar con la cátedra {} ({}): no se envió nada", operation, causeName);
                throw new CatedraUnavailableException("No se pudo conectar con la cátedra " + operation, e);
            }
            if (withEffects) {
                LOG.warn("Resultado desconocido {} en la cátedra ({}): pudo haberse aplicado", operation, causeName);
                throw new CatedraOutcomeUnknownException("La cátedra no respondió " + operation + ": resultado desconocido", e);
            }
            LOG.warn("La cátedra no respondió {} ({})", operation, causeName);
            throw new CatedraUnavailableException("La cátedra no respondió " + operation, e);
        }
    }

    private static byte[] readBody(ClientHttpResponse response, String operation, boolean withEffects, int expectedStatus)
        throws IOException {
        int status = response.getStatusCode().value();
        if (status == expectedStatus) {
            return response.getBody().readAllBytes();
        }
        String code = errorCode(response);
        String description = "La cátedra respondió " + status + " " + describe(code) + " " + operation;
        throw classify(status, code, description, withEffects);
    }

    private static CatedraException classify(int status, String code, String description, boolean withEffects) {
        CatedraErrorCode errorCode = CatedraErrorCode.from(code);
        if (status == 401) {
            return new CatedraAccessDeniedException(description);
        }
        if (status == 403) {
            // Spring Security responde 403 sin code (§13); STUDENT_INTEGRATION_NOT_FOUND es la cuenta técnica mal configurada.
            if (errorCode == CatedraErrorCode.UNRECOGNIZED || errorCode == CatedraErrorCode.STUDENT_INTEGRATION_NOT_FOUND) {
                return new CatedraAccessDeniedException(description);
            }
            return new CatedraRejectedException(status, errorCode, description);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return withEffects
                ? new CatedraOutcomeUnknownException(description + ": resultado desconocido")
                : new CatedraUnavailableException(description);
        }
        if (status >= 200 && status < 300) {
            // Un éxito con otro status: si la operación tiene efectos, se aplicó o no, pero no se sabe con qué datos.
            return withEffects
                ? new CatedraOutcomeUnknownException(description + " (status inesperado): resultado desconocido")
                : new CatedraInvalidResponseException(description + " (status inesperado)");
        }
        if (status >= 400 && status < 500 && code != null) {
            return new CatedraRejectedException(status, errorCode, description);
        }
        // 1xx, 3xx (no se siguen redirecciones) o un 4xx sin code.
        return new CatedraInvalidResponseException(description + " (status inesperado)");
    }

    /** El {@code code} del problem+json, si viene. Los 401 y 403 de Spring Security pueden no traerlo (§13). */
    private static String errorCode(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES);
            CatedraDtos.Problem problem = CatedraJson.MAPPER.readValue(body, CatedraDtos.Problem.class);
            return problem == null ? null : problem.code();
        } catch (IOException | JacksonException e) {
            // Sin cuerpo legible no hay code: el status alcanza para clasificar la falla.
            return null;
        }
    }

    private static String describe(String code) {
        if (code == null) {
            return "(sin code)";
        }
        return CODE_FORMAT.matcher(code).matches() ? "(code=" + code + ")" : "(code con formato inválido)";
    }
}
