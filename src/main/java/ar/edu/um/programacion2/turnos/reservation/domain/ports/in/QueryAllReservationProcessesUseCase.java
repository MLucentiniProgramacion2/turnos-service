package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AdminProcessQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import java.util.Optional;

/**
 * CU-12: los procesos de todos los usuarios, para diagnóstico. <strong>Solo lectura</strong> (DPT-14) y <strong>solo datos
 * locales</strong> (DPT-23): no consulta a la cátedra ni modifica nada.
 */
public interface QueryAllReservationProcessesUseCase {
    /** El listado, con filtros opcionales por dueño y estado. */
    ResultPage<ReservationProcess> list(AdminProcessQuery query);

    /** El detalle de un proceso, de cualquier dueño (5a: vacío si no existe). */
    Optional<ReservationProcess> find(ReservationProcessId id);
}
