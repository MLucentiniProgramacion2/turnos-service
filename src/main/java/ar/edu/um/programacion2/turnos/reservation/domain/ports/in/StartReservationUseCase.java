package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;

/** CU-04: iniciar una reserva (hold y confirmación inicial en una sola acción, DPT-03). */
public interface StartReservationUseCase {
    StartReservationResult start(StartReservationCommand command);
}
