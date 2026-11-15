package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Un evento del topic de acciones de la cátedra (contrato §15.4 a §15.10), ya leído y validado. Es {@code sealed}: cada
 * tipo es un record con los datos que necesita su transición, y el listener despacha con un {@code switch} completo.
 * <p>
 * Lo común a todos: el {@code eventId} (la clave de idempotencia, §15.1), el tipo y el {@code reservationProcessId} con el
 * que se busca el proceso. El {@code holdId} se usa solo para controlar que el evento corresponde al proceso: si no
 * coincide, se aplica igual por {@code reservationProcessId} y queda una advertencia. Lo mismo con el
 * {@code requestEventId} de los resultados.
 * <p>
 * Los resultados de CU-07 son {@link OutcomeEvent}: los datos que la transición no necesita para respetar un invariante
 * pueden faltar ({@code null}), y el caso de uso usa un valor de reemplazo (decisión del issue #12: no perder un resultado
 * real por un dato que no hace falta).
 */
public sealed interface ReservationEvent {
    String eventId();

    String reservationProcessId();

    /** El {@code holdId} del evento, o {@code null} si no vino. */
    String holdId();

    /** El {@code requestEventId} del evento, o {@code null} si no vino (o si el tipo no lo tiene). Solo para controlar. */
    default String requestEventId() {
        return null;
    }

    /** El {@code eventType} del contrato, tal como se registra en {@code processed_event}. */
    String eventType();

    /**
     * {@code AdditionalInformationRequested} (§15.4, CU-05). {@code expiresAt} es obligatorio: el proceso pasa a
     * {@code PHONE_REQUESTED} con el vencimiento informado. El {@code holdId} puede faltar: la transición no lo usa (el hold
     * guardado es el de CU-04) y el evento se aplica por {@code reservationProcessId}. El mensaje es para la app y puede faltar.
     */
    record PhoneRequested(String eventId, String reservationProcessId, String holdId, String message, Instant expiresAt) implements ReservationEvent {
        public static final String TYPE = "AdditionalInformationRequested";

        public PhoneRequested {
            Texts.requireText(eventId, "el eventId");
            Texts.requireText(reservationProcessId, "el reservationProcessId");
            Texts.optionalText(holdId, "el holdId");
            Texts.optionalText(message, "el mensaje");
            Objects.requireNonNull(expiresAt, "Falta el vencimiento");
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    /** Los cinco resultados del proceso (CU-07, contrato §15.6 a §15.10). */
    sealed interface OutcomeEvent extends ReservationEvent {}

    /**
     * {@code AppointmentConfirmed} (§15.6). {@code reservationId} es obligatorio: un {@code CONFIRMED} sin él no cumple el
     * {@code CHECK} de la tabla.
     *
     * @param occurredAt cuándo se confirmó; si no vino, {@code null}
     */
    record AppointmentConfirmed(String eventId, String reservationProcessId, String holdId, String requestEventId, long reservationId, Instant occurredAt)
        implements OutcomeEvent {
        public static final String TYPE = "AppointmentConfirmed";

        public AppointmentConfirmed {
            requireCommon(eventId, reservationProcessId);
            if (reservationId <= 0) {
                throw new IllegalArgumentException("El reservationId tiene que ser positivo");
            }
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    /**
     * {@code AdditionalInformationRejected} (§15.7). No es final: el proceso se puede volver a enviar hasta {@code expiresAt}.
     *
     * @param expiresAt el vencimiento nuevo, o {@code null} si no vino (se conserva el guardado)
     */
    record PhoneRejected(String eventId, String reservationProcessId, String holdId, String requestEventId, String reason, String message, Instant expiresAt)
        implements OutcomeEvent {
        public static final String TYPE = "AdditionalInformationRejected";

        public PhoneRejected {
            requireCommon(eventId, reservationProcessId);
            Texts.requireText(reason, "el motivo del rechazo");
            Texts.optionalText(message, "el mensaje del rechazo");
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    /** {@code AppointmentProcessExpired} (§15.8). {@code reason} es un código de la cátedra, como {@code HOLD_EXPIRED}. */
    record ProcessExpired(String eventId, String reservationProcessId, String holdId, String requestEventId, String reason) implements OutcomeEvent {
        public static final String TYPE = "AppointmentProcessExpired";

        public ProcessExpired {
            requireCommon(eventId, reservationProcessId);
            Texts.requireText(reason, "el motivo del vencimiento");
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    /** {@code AppointmentProcessInvalid} (§15.9). {@code reason} es un código, como {@code REQUEST_EVENT_MISMATCH}. */
    record ProcessInvalid(String eventId, String reservationProcessId, String holdId, String requestEventId, String reason) implements OutcomeEvent {
        public static final String TYPE = "AppointmentProcessInvalid";

        public ProcessInvalid {
            requireCommon(eventId, reservationProcessId);
            Texts.requireText(reason, "el motivo de la invalidez");
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    /**
     * {@code AppointmentCancelled} (§15.10). {@code reason} puede ser {@code null} y tiene como máximo
     * {@value Cancellation#MAX_REASON_LENGTH} caracteres, como en la cátedra.
     *
     * @param occurredAt cuándo se canceló; si no vino, {@code null}
     */
    record AppointmentCancelled(String eventId, String reservationProcessId, String holdId, String requestEventId, String reason, Instant occurredAt)
        implements OutcomeEvent {
        public static final String TYPE = "AppointmentCancelled";

        public AppointmentCancelled {
            requireCommon(eventId, reservationProcessId);
            Texts.optionalText(reason, "el motivo de la cancelación", Cancellation.MAX_REASON_LENGTH);
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }

    private static void requireCommon(String eventId, String reservationProcessId) {
        Texts.requireText(eventId, "el eventId");
        Texts.requireText(reservationProcessId, "el reservationProcessId");
    }
}
