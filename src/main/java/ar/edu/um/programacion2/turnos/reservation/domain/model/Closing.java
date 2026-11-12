package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Por qué terminó un proceso en {@code EXPIRED}, {@code INVALID} o {@code FAILED}: el {@code reason} del evento o el
 * {@code code} de la cátedra, o un motivo propio (por ejemplo, el resultado desconocido de la creación del hold, DPT-06).
 * El mensaje es opcional.
 */
public record Closing(String reason, String message) {
    public Closing {
        Texts.requireText(reason, "el motivo del cierre");
        Texts.optionalText(message, "el mensaje del cierre");
    }
}
