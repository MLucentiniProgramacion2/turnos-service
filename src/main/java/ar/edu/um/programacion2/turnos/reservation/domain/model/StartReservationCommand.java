package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * El pedido de iniciar una reserva (CU-04). {@code userId} y {@code login} salen del token, nunca de un dato enviado por el
 * cliente (DPT-21): los pone el controller.
 *
 * @param login el login del token, solo para la trazabilidad en el catálogo; puede ser {@code null}
 */
public record StartReservationCommand(long userId, String login, long professionalId, LocalDate date, LocalTime startTime) {
    public StartReservationCommand {
        if (userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Objects.requireNonNull(date, "Falta la fecha del turno");
        Objects.requireNonNull(startTime, "Falta la hora de inicio");
    }
}
