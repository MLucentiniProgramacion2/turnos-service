package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
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
    public static final String INVALID_SLOT = "INVALID_SLOT";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String PROFILE_INCOMPLETE = "PROFILE_INCOMPLETE";
    public static final String RESERVATION_IN_PROGRESS = "RESERVATION_IN_PROGRESS";
    public static final String SLOT_NOT_AVAILABLE = "SLOT_NOT_AVAILABLE";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String HOLD_OUTCOME_UNKNOWN = "HOLD_OUTCOME_UNKNOWN";
    public static final String RESERVATION_PROCESS_NOT_FOUND = "RESERVATION_PROCESS_NOT_FOUND";
    public static final String PHONE_NOT_REQUESTED = "PHONE_NOT_REQUESTED";
    public static final String PHONE_ALREADY_SUBMITTED = "PHONE_ALREADY_SUBMITTED";
    public static final String RESERVATION_PROCESS_CLOSED = "RESERVATION_PROCESS_CLOSED";
    public static final String INVALID_PHONE_NUMBER = "INVALID_PHONE_NUMBER";
    public static final String PHONE_SUBMISSION_UNCONFIRMED = "PHONE_SUBMISSION_UNCONFIRMED";

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

    // ---------------------------------------------------------------------------------------------------------------
    // CU-04
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code 400}: el turno ya empezó, la hora tiene segundos o no es un turno de un horario habilitado. */
    public static ErrorResponseException invalidSlot() {
        return problem(HttpStatus.BAD_REQUEST, INVALID_SLOT, "El turno no es válido o ya no está habilitado");
    }

    /**
     * {@code 401}: el token es válido, pero su usuario ya no existe. Es lo que la app ya sabe tratar: vuelve al login, que
     * va a fallar.
     */
    public static ErrorResponseException userNotFound() {
        return problem(HttpStatus.UNAUTHORIZED, USER_NOT_FOUND, "El usuario ya no existe");
    }

    /** {@code 409}: el nombre o el apellido del perfil no sirven como nombre del paciente (2 a 100 caracteres). */
    public static ErrorResponseException profileIncomplete() {
        return problem(HttpStatus.CONFLICT, PROFILE_INCOMPLETE, "Completá el nombre y el apellido de tu perfil para poder reservar");
    }

    /**
     * {@code 409}: el usuario ya tiene una reserva en curso para otro turno (DPT-17). Lleva el {@code processId} de ese
     * proceso, para que la app lo pueda abrir: es del mismo usuario, así que no revela nada ajeno.
     *
     * @param existing puede ser {@code null} si el proceso terminó justo antes de leerlo
     */
    public static ErrorResponseException reservationInProgress(ReservationProcessId existing) {
        ErrorResponseException response = problem(HttpStatus.CONFLICT, RESERVATION_IN_PROGRESS, "Ya tenés una reserva en curso");
        if (existing != null) {
            response.getBody().setProperty("processId", existing.value().toString());
        }
        return response;
    }

    public static ErrorResponseException slotNotAvailable() {
        return problem(HttpStatus.CONFLICT, SLOT_NOT_AVAILABLE, "El turno ya no está disponible");
    }

    /** {@code 409}: venció el tiempo para completar la reserva (CU-04 11b, CU-06 3d). */
    public static ErrorResponseException reservationExpired() {
        return problem(HttpStatus.CONFLICT, RESERVATION_EXPIRED, "Venció el tiempo para completar la reserva");
    }

    /** {@code 503}: no se sabe si el hold se creó (DPT-06). El aviso es parte del caso de uso (8a). */
    public static ErrorResponseException holdOutcomeUnknown() {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            HOLD_OUTCOME_UNKNOWN,
            "No se pudo confirmar la reserva. El horario puede quedar bloqueado unos minutos: probá con otro turno o más tarde"
        );
    }

    /** {@code 500}: el hold se creó en la cátedra, pero turnos no lo pudo guardar. El horario queda bloqueado hasta que venza. */
    public static ErrorResponseException holdNotRecorded() {
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            INTERNAL_ERROR,
            "No se pudo registrar la reserva. El horario puede quedar bloqueado unos minutos: probá con otro turno o más tarde"
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CU-06
    // ---------------------------------------------------------------------------------------------------------------

    /** {@code 404}: el proceso no existe <strong>o es de otro usuario</strong>; la respuesta es la misma (DPT-19). */
    public static ErrorResponseException processNotFound() {
        return problem(HttpStatus.NOT_FOUND, RESERVATION_PROCESS_NOT_FOUND, "El proceso de reserva no existe");
    }

    public static ErrorResponseException phoneNotRequested() {
        return problem(HttpStatus.CONFLICT, PHONE_NOT_REQUESTED, "Todavía no se pidió el teléfono");
    }

    public static ErrorResponseException phoneAlreadySubmitted() {
        return problem(HttpStatus.CONFLICT, PHONE_ALREADY_SUBMITTED, "El teléfono ya se envió: hay que esperar el resultado");
    }

    /** {@code 409}: el proceso está confirmado o cerrado. Lleva el {@code status} actual, que la app puede mostrar. */
    public static ErrorResponseException processClosed(ReservationProcessStatus status) {
        ErrorResponseException response = problem(HttpStatus.CONFLICT, RESERVATION_PROCESS_CLOSED, "El proceso de reserva ya terminó");
        response.getBody().setProperty("status", status.name());
        return response;
    }

    /** {@code 400}: el teléfono no cumple el formato que acepta la cátedra. No repite el valor (es un dato personal). */
    public static ErrorResponseException invalidPhone() {
        return problem(HttpStatus.BAD_REQUEST, INVALID_PHONE_NUMBER, "El teléfono no tiene un formato válido");
    }

    /** {@code 503}: Kafka no confirmó. El proceso no cambió y se puede volver a enviar (CU-06 6a). */
    public static ErrorResponseException phoneSubmissionUnconfirmed() {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            PHONE_SUBMISSION_UNCONFIRMED,
            "No se pudo confirmar el envío del teléfono. Podés volver a enviarlo"
        );
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
