package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Los procesos de reserva guardados. Las operaciones son las que piden los casos de uso (CU-04 en adelante), no un CRUD.
 * <p>
 * <strong>La transacción la abre el caso de uso</strong>: todas las operaciones exigen una abierta. Las búsquedas de los
 * endpoints llevan siempre el dueño ({@code userId}): un proceso ajeno es lo mismo que uno que no existe (DPT-19).
 */
public interface ReservationProcessRepository {
    /**
     * Guarda un proceso nuevo. El {@code INSERT} se ejecuta antes de volver, así una violación del índice sale de acá y
     * no del commit.
     *
     * @return el proceso con su versión, listo para la próxima escritura
     * @throws IntermediateProcessConflictException si el usuario ya tiene un proceso intermedio (DPT-17)
     */
    ReservationProcess insert(ReservationProcess process);

    /**
     * Guarda los cambios de un proceso que ya existe. Si otra transacción lo modificó desde que se leyó, lanza la
     * excepción del bloqueo optimista ({@code OptimisticLockingFailureException}) en vez de pisar el cambio.
     *
     * @return el proceso con la versión nueva
     */
    ReservationProcess save(ReservationProcess process);

    /** El proceso intermedio del usuario: hay uno como máximo (DPT-17). */
    Optional<ReservationProcess> findIntermediateByUser(long userId);

    /**
     * El proceso del usuario para ese turno que está en curso o confirmado (CU-04 paso 3). Si hubiera más de uno, primero
     * el intermedio y después el confirmado más nuevo.
     */
    Optional<ReservationProcess> findActiveBySlot(long userId, long professionalId, LocalDate date, LocalTime startTime);

    /**
     * El proceso con ese {@code reservationProcessId} de la cátedra (los eventos de Kafka llegan con ese ID). Es único en la
     * tabla; en {@code CREATING_HOLD} todavía no lo tiene, así que ningún evento lo encuentra.
     */
    Optional<ReservationProcess> findByReservationProcessId(String reservationProcessId);

    /** El proceso con ese identificador <strong>si es de ese usuario</strong>. */
    Optional<ReservationProcess> findByIdAndOwner(ReservationProcessId id, long userId);
}
