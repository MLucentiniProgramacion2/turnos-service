package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentCancelled;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentConfirmed;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.PhoneRejected;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.PhoneRequested;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessExpired;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessInvalid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el texto de un mensaje del topic de acciones en un {@link ParsedEvent}.
 * <p>
 * <strong>Obligatorio es solo lo que hace falta para aplicar la transición sin romper un invariante</strong>: el
 * {@code eventId}, el {@code eventType}, el {@code reservationProcessId}, el {@code reservationId} de una confirmación
 * (el {@code CHECK} de {@code CONFIRMED} lo exige) y, en el pedido de teléfono, el {@code holdId} y el {@code expiresAt}.
 * Lo demás <strong>se degrada con una advertencia</strong> en vez de descartar el evento (decisión del issue #12): un mal
 * formado se confirma sin aplicar, y sin la reconciliación (CU-11) el resultado se perdería por un dato que no hace falta.
 * <ul>
 * <li>{@code occurredAt} ausente o ilegible: el instante del reloj (lo pone el caso de uso);</li>
 * <li>{@code reason} ausente o en blanco en un rechazo, vencimiento o invalidez: {@value #UNSPECIFIED};</li>
 * <li>{@code expiresAt} ausente o ilegible en un rechazo: se conserva el guardado;</li>
 * <li>{@code reason} de una cancelación de más de {@value Cancellation#MAX_REASON_LENGTH} caracteres: se recorta.</li>
 * </ul>
 * Mapper propio: el resultado no depende de {@code spring.jackson.*}. {@code ACCEPT_FLOAT_AS_INT} va apagado: Jackson
 * truncaría {@code 8251.5} a {@code 8251} en silencio. Un texto en blanco cuenta como ausente.
 */
final class ReservationEventMessageMapper {

    /** El motivo que se guarda cuando la cátedra no mandó uno. */
    static final String UNSPECIFIED = "UNSPECIFIED";

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    private ReservationEventMessageMapper() {}

    /** @param payload el valor del mensaje; {@code null} si es un tombstone */
    static ParsedEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ParsedEvent.Malformed(null, "mensaje vacío");
        }
        ReservationEventMessage message;
        try {
            message = JSON.readValue(payload, ReservationEventMessage.class);
        } catch (JacksonException e) {
            // Sin la excepción: su mensaje puede traer parte del contenido.
            return new ParsedEvent.Malformed(null, "no es el JSON esperado");
        }
        if (message == null) {
            return new ParsedEvent.Malformed(null, "no es el JSON esperado");
        }
        String eventId = text(message.eventId());
        if (eventId == null) {
            return new ParsedEvent.Malformed(null, "falta eventId");
        }
        String eventType = text(message.eventType());
        if (eventType == null) {
            return new ParsedEvent.Malformed(eventId, "falta eventType");
        }
        boolean known = switch (eventType) {
            case PhoneRequested.TYPE,
                AppointmentConfirmed.TYPE,
                PhoneRejected.TYPE,
                ProcessExpired.TYPE,
                ProcessInvalid.TYPE,
                AppointmentCancelled.TYPE -> true;
            default -> false;
        };
        if (!known) {
            return new ParsedEvent.UnknownType(eventId);
        }
        String reservationProcessId = text(message.reservationProcessId());
        if (reservationProcessId == null) {
            return new ParsedEvent.Malformed(eventId, "falta reservationProcessId");
        }
        return new Reader(eventId, reservationProcessId, message).read(eventType);
    }

    /** Lee un tipo y junta las degradaciones. */
    private static final class Reader {

        private final String eventId;
        private final String reservationProcessId;
        private final ReservationEventMessage message;
        private final List<String> degradations = new ArrayList<>();

        private Reader(String eventId, String reservationProcessId, ReservationEventMessage message) {
            this.eventId = eventId;
            this.reservationProcessId = reservationProcessId;
            this.message = message;
        }

        ParsedEvent read(String eventType) {
            String holdId = text(message.holdId());
            String requestEventId = text(message.requestEventId());
            return switch (eventType) {
                case PhoneRequested.TYPE -> phoneRequested(holdId);
                case AppointmentConfirmed.TYPE -> {
                    Long reservationId = message.reservationId();
                    if (reservationId == null || reservationId <= 0) {
                        yield new ParsedEvent.Malformed(eventId, "falta reservationId o no es positivo");
                    }
                    yield event(new AppointmentConfirmed(eventId, reservationProcessId, holdId, requestEventId, reservationId, occurredAt()));
                }
                case PhoneRejected.TYPE -> event(
                    new PhoneRejected(eventId, reservationProcessId, holdId, requestEventId, reason(), text(message.message()), rejectionExpiresAt())
                );
                case ProcessExpired.TYPE -> event(new ProcessExpired(eventId, reservationProcessId, holdId, requestEventId, reason()));
                case ProcessInvalid.TYPE -> event(new ProcessInvalid(eventId, reservationProcessId, holdId, requestEventId, reason()));
                case AppointmentCancelled.TYPE -> event(
                    new AppointmentCancelled(eventId, reservationProcessId, holdId, requestEventId, cancellationReason(), occurredAt())
                );
                default -> throw new IllegalStateException("Tipo sin lector: " + eventType);
            };
        }

        /** {@code AdditionalInformationRequested} (§15.4): sin {@code holdId} ni {@code expiresAt}, el modelo no lo puede aplicar. */
        private ParsedEvent phoneRequested(String holdId) {
            if (holdId == null) {
                return new ParsedEvent.Malformed(eventId, "falta holdId");
            }
            Instant expiresAt = instant(message.expiresAt());
            if (expiresAt == null) {
                return new ParsedEvent.Malformed(eventId, "falta expiresAt o no es un instante ISO-8601");
            }
            return event(new PhoneRequested(eventId, reservationProcessId, holdId, text(message.message()), expiresAt));
        }

        private Instant occurredAt() {
            Instant occurredAt = instant(message.occurredAt());
            if (occurredAt == null) {
                degradations.add("occurredAt ausente o ilegible: se usa el reloj");
            }
            return occurredAt;
        }

        private Instant rejectionExpiresAt() {
            Instant expiresAt = instant(message.expiresAt());
            if (expiresAt == null) {
                degradations.add("expiresAt ausente o ilegible: se conserva el vencimiento guardado");
            }
            return expiresAt;
        }

        private String reason() {
            String reason = text(message.reason());
            if (reason == null) {
                degradations.add("reason ausente: se guarda " + UNSPECIFIED);
                return UNSPECIFIED;
            }
            return reason;
        }

        /** La cancelación admite hasta 500 caracteres, como la cátedra (§12): uno más largo se recorta, no se pierde. */
        private String cancellationReason() {
            String reason = text(message.reason());
            if (reason != null && reason.length() > Cancellation.MAX_REASON_LENGTH) {
                degradations.add("reason de " + reason.length() + " caracteres: se recorta a " + Cancellation.MAX_REASON_LENGTH);
                return reason.substring(0, Cancellation.MAX_REASON_LENGTH);
            }
            return reason;
        }

        private ParsedEvent event(ReservationEvent event) {
            return new ParsedEvent.Event(event, degradations);
        }
    }

    /** El texto, o {@code null} si falta o está en blanco. */
    static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** El instante ISO-8601 (§4), o {@code null} si falta o no se puede leer. */
    static Instant instant(String value) {
        if (text(value) == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
