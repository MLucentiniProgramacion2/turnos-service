package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.GetReservationProcessUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CU-08: devuelve el proceso guardado, buscado por su id <strong>y</strong> por el dueño (DPT-21). Uno ajeno es lo mismo que
 * uno que no existe (DPT-19).
 * <p>
 * La app lo consulta cada 2 segundos mientras la pantalla está abierta (DPT-04), así que es lo más barato posible: una
 * sola lectura, en una transacción de solo lectura, sin catálogo ni cátedra (DPT-12) y sin log por consulta (solo DEBUG
 * cuando no se encuentra).
 * <p>
 * <strong>No modifica nada</strong>, aunque haya pasado {@code expiresAt}: cerrar el proceso es de CU-07 (el evento de la
 * cátedra) y de CU-11 (la reconciliación). Una consulta que cerrara procesos por su cuenta le ganaría a la cátedra, que
 * tiene la última palabra.
 */
@Component
@RequiredArgsConstructor
public class GetReservationProcessUseCaseImpl implements GetReservationProcessUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(GetReservationProcessUseCaseImpl.class);

    private final ReservationProcessRepository repository;
    private final ReadTransactions transactions;

    @Override
    public Optional<ReservationProcess> get(long userId, ReservationProcessId processId) {
        Objects.requireNonNull(processId, "Falta el identificador del proceso");
        Optional<ReservationProcess> found = transactions.template().execute(status -> repository.findByIdAndOwner(processId, userId));
        if (found.isEmpty()) {
            LOG.debug("CU-08: el proceso {} no existe o no es del usuario {}", processId, userId);
        }
        return found;
    }
}
