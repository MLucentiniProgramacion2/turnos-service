package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;

/**
 * Los errores REST de la reserva (contrato en {@code docs/api-rest.md}), como {@code CatalogErrorResponses} del catálogo.
 * <p>
 * Los controllers lanzan una {@link ErrorResponseException} con un {@link ProblemDetailWithCause} que lleva el
 * {@code code}: el {@code ExceptionTranslator} de JHipster reconoce ese cuerpo, lo conserva y le agrega {@code type},
 * {@code title}, {@code message} y {@code path}. Así el formato se arma en un solo lugar y JHipster no depende de la
 * feature.
 * <p>
 * La app decide por {@code status} y {@code code}. Los {@code detail} son <strong>texto fijo</strong>: nunca el
 * {@code detail} de la cátedra, el valor recibido, un nombre de clase ni el motivo técnico de una falla (DPT-18). El
 * detalle va al log.
 */
public final class ReservationErrorResponses {

    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
    public static final String PROFESSIONAL_NOT_FOUND = "PROFESSIONAL_NOT_FOUND";
    public static final String PROFESSIONAL_DISABLED = "PROFESSIONAL_DISABLED";
    public static final String CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE";
    public static final String CATEDRA_UNAVAILABLE = "CATEDRA_UNAVAILABLE";
    public static final String UPSTREAM_INVALID_RESPONSE = "UPSTREAM_INVALID_RESPONSE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final String CODE = "code";

    private ReservationErrorResponses() {}

    /** {@code 400}: el rango o la fecha no cumplen DPT-10. */
    public static ErrorResponseException invalidDateRange(String detail) {
        return problem(HttpStatus.BAD_REQUEST, INVALID_DATE_RANGE, detail);
    }

    public static ErrorResponseException professionalNotFound() {
        return problem(HttpStatus.NOT_FOUND, PROFESSIONAL_NOT_FOUND, "El profesional no existe");
    }

    public static ErrorResponseException professionalDisabled() {
        return problem(HttpStatus.BAD_REQUEST, PROFESSIONAL_DISABLED, "El profesional no está habilitado");
    }

    /**
     * {@code 503}: sin agenda vigente no se puede operar (DPT-11). Con {@code Retry-After} en segundos si el catálogo
     * informó cuándo vuelve a revisar si tiene datos: es un servicio propio, el valor ya viene acotado a una hora, y le
     * dice a la app cuándo conviene reintentar. Sin ese dato no se manda: un valor inventado prometería algo que nadie
     * cumple.
     */
    public static ErrorResponseException catalogUnavailable(Duration retryAfter) {
        ErrorResponseException response = problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            CATALOG_UNAVAILABLE,
            "El catálogo de profesionales no está disponible en este momento"
        );
        if (retryAfter != null) {
            // Segundos enteros (RFC 9110), redondeados para arriba para no prometer antes de tiempo.
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.ceilDiv(retryAfter.toMillis(), 1000)));
        }
        return response;
    }

    public static ErrorResponseException catedraUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, CATEDRA_UNAVAILABLE, "El servicio de turnos no está disponible en este momento");
    }

    /** {@code 502}: no es un error nuestro ({@code 500}) ni una caída temporal ({@code 503}). */
    public static ErrorResponseException upstreamInvalidResponse() {
        return problem(HttpStatus.BAD_GATEWAY, UPSTREAM_INVALID_RESPONSE, "Un servicio respondió de una forma inesperada");
    }

    public static ErrorResponseException internalError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "Error interno");
    }

    static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetailWithCause problem = ProblemDetailWithCauseBuilder.instance()
            .withStatus(status.value())
            .withDetail(detail)
            .withProperty(CODE, code)
            .build();
        return new ErrorResponseException(status, problem, null);
    }
}
