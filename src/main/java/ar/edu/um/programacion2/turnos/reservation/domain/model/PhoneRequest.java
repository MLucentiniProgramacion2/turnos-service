package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * El pedido de teléfono de la cátedra ({@code AdditionalInformationRequested}, contrato §15.4). Su {@code eventId} se
 * guarda para mandarlo como {@code requestEventId} en cada envío del teléfono; el mensaje es para mostrar en la app y
 * puede faltar.
 */
public record PhoneRequest(String requestEventId, String message) {
    public PhoneRequest {
        Texts.requireText(requestEventId, "el requestEventId");
        Texts.optionalText(message, "el mensaje del pedido");
    }
}
