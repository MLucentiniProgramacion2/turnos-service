package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationResult;

/** CU-10: cancelar una reserva propia confirmada, de forma idempotente. */
public interface CancelReservationUseCase {
    CancelReservationResult cancel(CancelReservationCommand command);
}
