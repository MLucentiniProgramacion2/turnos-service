package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Un evento del topic de acciones de la cátedra (contrato §15.4 a §15.10), ya leído y validado. Es {@code sealed}: cada
 * tipo es un record con los datos que necesita su transición, y el listener despacha con un {@code switch} completo.
 * <p>
 * Lo común a todos: el {@code eventId} (la clave de idempotencia, §15.1), el tipo y el {@code reservationProcessId} con el
 * que se busca el proceso. El {@code holdId} se usa solo para controlar que el evento corresponde al proceso: si no
 * coincide, se aplica igual por {@code reservationProcessId} y queda una advertencia.
 */
public sealed interface ReservationEvent {
    String eventId();

    String reservationProcessId();

    /** El {@code holdId} del evento, o {@code null} si no vino. */
    String holdId();

    /** El {@code eventType} del contrato, tal como se registra en {@code processed_event}. */
    String eventType();

    /**
     * {@code AdditionalInformationRequested} (§15.4, CU-05). {@code holdId} y {@code expiresAt} son obligatorios: el proceso
     * pasa a {@code PHONE_REQUESTED} con el vencimiento informado. El mensaje es para la app y puede faltar.
     */
    record PhoneRequested(String eventId, String reservationProcessId, String holdId, String message, Instant expiresAt) implements ReservationEvent {
        public static final String TYPE = "AdditionalInformationRequested";

        public PhoneRequested {
            Texts.requireText(eventId, "el eventId");
            Texts.requireText(reservationProcessId, "el reservationProcessId");
            Texts.requireText(holdId, "el holdId");
            Texts.optionalText(message, "el mensaje");
            Objects.requireNonNull(expiresAt, "Falta el vencimiento");
        }

        @Override
        public String eventType() {
            return TYPE;
        }
    }
}
