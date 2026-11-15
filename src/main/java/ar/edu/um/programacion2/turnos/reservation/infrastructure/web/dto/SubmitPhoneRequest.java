package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/reservation-processes/{id}/phone} (CU-06). El teléfono va como lo escribió el usuario: lo
 * normaliza el dominio igual que la cátedra (quita espacios, guiones, paréntesis y puntos). Acá solo se exige que venga y
 * un largo máximo, para no procesar textos enormes; si el formato sirve lo decide el dominio ({@code INVALID_PHONE_NUMBER}).
 * <p>
 * Es un dato personal: el {@code toString()} no lo muestra.
 */
public record SubmitPhoneRequest(@NotNull @Size(max = SubmitPhoneRequest.MAX_LENGTH) String phoneNumber) {
    public static final int MAX_LENGTH = 40;

    @Override
    public String toString() {
        return "SubmitPhoneRequest[phoneNumber=***]";
    }
}
