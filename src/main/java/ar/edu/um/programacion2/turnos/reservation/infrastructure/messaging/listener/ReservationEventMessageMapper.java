package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el texto de un mensaje del topic de acciones en un {@link ParsedEvent}. Valida solo lo que hace falta para
 * aplicar la transición sin romper un invariante del modelo: no se rechaza un dato real por algo que no se usa.
 * <p>
 * Mapper propio, como en el catálogo: el resultado no depende de {@code spring.jackson.*}. {@code ACCEPT_FLOAT_AS_INT} va
 * apagado: Jackson truncaría {@code 8251.5} a {@code 8251} en silencio. Un texto en blanco cuenta como ausente: el modelo
 * no admite textos en blanco y fallaría adentro de la transacción, reintentando algo que nunca va a andar.
 */
final class ReservationEventMessageMapper {

    /** Los cinco tipos de resultado de CU-07 (contrato §15.6 a §15.10). */
    static final Set<String> OUTCOME_TYPES = Set.of(
        "AppointmentConfirmed",
        "AdditionalInformationRejected",
        "AppointmentProcessExpired",
        "AppointmentProcessInvalid",
        "AppointmentCancelled"
    );

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
        if (OUTCOME_TYPES.contains(eventType)) {
            return new ParsedEvent.NotYetSupported(eventType, eventId);
        }
        if (!ReservationEvent.PhoneRequested.TYPE.equals(eventType)) {
            return new ParsedEvent.UnknownType(eventId);
        }
        return phoneRequested(eventId, message);
    }

    /** {@code AdditionalInformationRequested} (§15.4): sin {@code holdId} ni {@code expiresAt}, el modelo no lo puede aplicar. */
    private static ParsedEvent phoneRequested(String eventId, ReservationEventMessage message) {
        String reservationProcessId = text(message.reservationProcessId());
        if (reservationProcessId == null) {
            return new ParsedEvent.Malformed(eventId, "falta reservationProcessId");
        }
        String holdId = text(message.holdId());
        if (holdId == null) {
            return new ParsedEvent.Malformed(eventId, "falta holdId");
        }
        Instant expiresAt = instant(message.expiresAt());
        if (expiresAt == null) {
            return new ParsedEvent.Malformed(eventId, "falta expiresAt o no es un instante ISO-8601");
        }
        return new ParsedEvent.Event(new ReservationEvent.PhoneRequested(eventId, reservationProcessId, holdId, text(message.message()), expiresAt));
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
