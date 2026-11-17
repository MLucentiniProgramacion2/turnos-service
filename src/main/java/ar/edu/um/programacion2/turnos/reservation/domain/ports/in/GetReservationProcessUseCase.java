package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import java.util.Optional;

/**
 * CU-08: el estado de un proceso propio, tal como está guardado. Los resultados son dos ("está" o "no está", y "no está"
 * incluye uno ajeno, DPT-19), así que alcanza un {@link Optional}: un {@code sealed} de dos casos no agregaría nada.
 */
public interface GetReservationProcessUseCase {
    Optional<ReservationProcess> get(long userId, ReservationProcessId processId);
}
