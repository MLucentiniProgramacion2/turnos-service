package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * El envío del teléfono a la cátedra ({@code AdditionalInformationSubmitted}, contrato §15.5), con los datos que salen del
 * proceso. El {@code groupId} no está: es de la integración y lo agrega el adaptador.
 * <p>
 * El teléfono es un dato personal: el {@code toString()} de este record usa el de {@link PhoneNumber}, que lo enmascara.
 *
 * @param eventId       nuevo en cada envío (también en un reenvío después de un rechazo, §15.7)
 * @param requestEventId el {@code eventId} del {@code AdditionalInformationRequested} que se responde
 * @param occurredAt    cuándo se envió, en UTC
 */
public record PhoneSubmission(
    String eventId,
    String reservationProcessId,
    String holdId,
    String requestEventId,
    PhoneNumber phone,
    Instant occurredAt
) {
    public PhoneSubmission {
        Texts.requireText(eventId, "el eventId");
        Texts.requireText(reservationProcessId, "el reservationProcessId");
        Texts.requireText(holdId, "el holdId");
        Texts.requireText(requestEventId, "el requestEventId");
        Objects.requireNonNull(phone, "Falta el teléfono");
        Objects.requireNonNull(occurredAt, "Falta el instante del envío");
    }
}
