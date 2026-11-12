package ar.edu.um.programacion2.turnos.reservation.domain.model;

/** El último rechazo del teléfono ({@code AdditionalInformationRejected}, contrato §15.7). No es final: se puede reenviar. */
public record PhoneRejection(String reason, String message) {
    public PhoneRejection {
        Texts.requireText(reason, "el motivo del rechazo");
        Texts.optionalText(message, "el mensaje del rechazo");
    }
}
