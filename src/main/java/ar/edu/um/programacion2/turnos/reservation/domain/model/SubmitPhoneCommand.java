package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * El pedido de informar el teléfono (CU-06). {@code userId} sale del token (DPT-21): el proceso se busca por su id
 * <strong>y</strong> por su dueño. El teléfono llega tal como lo escribió el usuario; lo normaliza {@link PhoneNumber}.
 * Su {@code toString()} no lo muestra.
 */
public record SubmitPhoneCommand(long userId, ReservationProcessId processId, String rawPhone) {
    public SubmitPhoneCommand {
        if (userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Objects.requireNonNull(processId, "Falta el proceso");
        Objects.requireNonNull(rawPhone, "Falta el teléfono");
    }

    @Override
    public String toString() {
        return "SubmitPhoneCommand[userId=" + userId + ", processId=" + processId + ", rawPhone=***]";
    }
}
