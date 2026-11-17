package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AdminProcessQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CU-12 con el puerto de persistencia en memoria. */
class QueryAllReservationProcessesUseCaseImplTest {

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final QueryAllReservationProcessesUseCaseImpl casoDeUso = new QueryAllReservationProcessesUseCaseImpl(procesos, new ReadTransactions(transacciones));

    @BeforeEach
    void procesosDeDosUsuarios() {
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.CONFIRMED, 7, LocalTime.of(9, 0), 1));
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.PHONE_REQUESTED, 7, LocalTime.of(10, 0), 2));
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.CONFIRMED, 8, LocalTime.of(11, 0), 3));
    }

    @Test
    void cu12_principal_losProcesosDeTodosLosUsuarios() {
        ResultPage<ReservationProcess> pagina = casoDeUso.list(new AdminProcessQuery(null, Set.of(), new PageQuery(0, 20)));

        assertThat(pagina.totalElements()).isEqualTo(3);
        assertThat(pagina.items()).extracting(proceso -> proceso.getOwner().userId()).containsExactlyInAnyOrder(7L, 7L, 8L);
    }

    @Test
    void cu12_1_filtrosPorDuenoYPorEstado() {
        assertThat(casoDeUso.list(new AdminProcessQuery(7L, Set.of(), new PageQuery(0, 20))).totalElements()).isEqualTo(2);
        assertThat(casoDeUso.list(new AdminProcessQuery(null, Set.of(ReservationProcessStatus.CONFIRMED), new PageQuery(0, 20))).totalElements()).isEqualTo(2);
        assertThat(casoDeUso.list(new AdminProcessQuery(8L, Set.of(ReservationProcessStatus.CONFIRMED), new PageQuery(0, 20))).items())
            .singleElement()
            .satisfies(proceso -> assertThat(proceso.getId()).isEqualTo(id(3)));
    }

    @Test
    void cu12_3a_ningunoCumpleLosFiltrosEsUnaListaVacia() {
        assertThat(casoDeUso.list(new AdminProcessQuery(99L, Set.of(), new PageQuery(0, 20))).items()).isEmpty();
        assertThat(casoDeUso.list(new AdminProcessQuery(null, Set.of(ReservationProcessStatus.INVALID), new PageQuery(0, 20))).totalElements()).isZero();
    }

    @Test
    void cu12_5_elDetalleDeCualquierDuenoY5aElQueNoExiste() {
        assertThat(casoDeUso.find(id(3))).hasValueSatisfying(proceso -> assertThat(proceso.getOwner().userId()).isEqualTo(8));
        assertThat(casoDeUso.find(id(99))).isEmpty();
    }

    @Test
    void cu12_soloLecturaNoEscribeNada() {
        casoDeUso.list(new AdminProcessQuery(null, Set.of(), new PageQuery(0, 20)));
        casoDeUso.find(id(1));

        assertThat(procesos.writes()).isEmpty();
    }
}
