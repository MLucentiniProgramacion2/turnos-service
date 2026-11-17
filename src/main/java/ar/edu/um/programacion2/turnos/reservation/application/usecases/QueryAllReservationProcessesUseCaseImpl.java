package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AdminProcessQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.QueryAllReservationProcessesUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CU-12: lee la tabla local, de todos los usuarios, en una transacción de solo lectura (la página y el total de la misma
 * foto). No llama a la cátedra (DPT-23) ni escribe nada (DPT-14). La autorización por rol la hace la regla de
 * {@code /api/admin/**}: este caso de uso solo existe detrás de ella.
 */
@Component
@RequiredArgsConstructor
public class QueryAllReservationProcessesUseCaseImpl implements QueryAllReservationProcessesUseCase {

    private final ReservationProcessRepository repository;
    private final ReadTransactions readTransactions;

    @Override
    public ResultPage<ReservationProcess> list(AdminProcessQuery query) {
        Objects.requireNonNull(query, "Falta la consulta");
        return readTransactions.template().execute(status -> repository.findPage(query.userId(), query.statuses(), query.page()));
    }

    @Override
    public Optional<ReservationProcess> find(ReservationProcessId id) {
        Objects.requireNonNull(id, "Falta el identificador");
        return readTransactions.template().execute(status -> repository.findById(id));
    }
}
