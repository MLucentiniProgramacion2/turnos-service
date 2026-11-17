package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * El pedido de cancelar una reserva propia (CU-10). {@code userId} sale del token (DPT-21). El motivo lo escribe el
 * usuario y va a la cátedra, <strong>nunca al log</strong>: el {@code toString()} no lo muestra. Uno en blanco es "sin
 * motivo".
 *
 * @param reason opcional, hasta {@value Cancellation#MAX_REASON_LENGTH} caracteres (el máximo de la cátedra, §12)
 */
public record CancelReservationCommand(long userId, ReservationProcessId processId, String reason) {
    public CancelReservationCommand {
        if (userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Objects.requireNonNull(processId, "Falta el proceso");
        reason = reason == null || reason.isBlank() ? null : reason;
        if (reason != null && reason.length() > Cancellation.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("El motivo supera los " + Cancellation.MAX_REASON_LENGTH + " caracteres");
        }
    }

    @Override
    public String toString() {
        return "CancelReservationCommand[userId=" + userId + ", processId=" + processId + ", reason=" + (reason == null ? "null" : "***") + "]";
    }
}
