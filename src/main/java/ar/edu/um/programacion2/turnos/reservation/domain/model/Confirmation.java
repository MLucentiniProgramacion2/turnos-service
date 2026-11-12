package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/** La reserva confirmada por la cátedra ({@code AppointmentConfirmed} o reconciliación). */
public record Confirmation(long reservationId, Instant confirmedAt) {
    public Confirmation {
        if (reservationId <= 0) {
            throw new IllegalArgumentException("El reservationId tiene que ser positivo");
        }
        Objects.requireNonNull(confirmedAt, "Falta el instante de confirmación");
    }
}
