package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservations;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservationsQuery;

/**
 * CU-09: los procesos y reservas propios, con el estado de la cátedra cuando está disponible. Un solo resultado posible
 * (la falla de la cátedra es la marca de "no actualizado", no un error), así que no hace falta un {@code sealed}.
 */
public interface ListMyReservationsUseCase {
    MyReservations list(MyReservationsQuery query);
}
